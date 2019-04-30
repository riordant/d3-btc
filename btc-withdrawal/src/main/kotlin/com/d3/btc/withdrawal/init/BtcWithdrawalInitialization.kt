/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.init

import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.btc.healthcheck.HealthyService
import com.d3.btc.helper.network.addPeerConnectionStatusListener
import com.d3.btc.helper.network.startChainDownload
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.checkWalletNetwork
import com.d3.btc.withdrawal.config.BTC_WITHDRAWAL_SERVICE_NAME
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.handler.NewChangeAddressHandler
import com.d3.btc.withdrawal.handler.NewConsensusDataHandler
import com.d3.btc.withdrawal.handler.NewSignatureEventHandler
import com.d3.btc.withdrawal.handler.NewTransferHandler
import com.d3.commons.config.RMQConfig
import com.d3.commons.sidechain.iroha.BTC_CONSENSUS_DOMAIN
import com.d3.commons.sidechain.iroha.BTC_SIGN_COLLECT_DOMAIN
import com.d3.commons.sidechain.iroha.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.sidechain.iroha.util.getTransferCommands
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.BlockOuterClass
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.Closeable
import java.io.File

/*
    Class that initiates listeners that will be used to handle Bitcoin withdrawal logic
 */
@Component
class BtcWithdrawalInitialization(
    @Autowired private val btcWithdrawalConfig: BtcWithdrawalConfig,
    @Autowired private val peerGroup: PeerGroup,
    @Autowired private val transferWallet: Wallet,
    @Autowired private val btcChangeAddressProvider: BtcChangeAddressProvider,
    @Autowired private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    @Autowired private val newSignatureEventHandler: NewSignatureEventHandler,
    @Autowired private val newBtcClientRegistrationHandler: NewBtcClientRegistrationHandler,
    @Autowired private val newTransferHandler: NewTransferHandler,
    @Autowired private val newChangeAddressHandler: NewChangeAddressHandler,
    @Autowired private val newConsensusDataHandler: NewConsensusDataHandler,
    @Autowired private val rmqConfig: RMQConfig
) : HealthyService(), Closeable {

    private val irohaChainListener = ReliableIrohaChainListener(
        rmqConfig, btcWithdrawalConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_WITHDRAWAL_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = false,
        subscribe = { block, ack ->
            safeApplyAck({ handleIrohaBlock(block) }, { ack() })
        }
    )

    fun init(): Result<Unit, Exception> {
        //TODO create a fee rate updating mechanism
        //Set minimum fee rate
        CurrentFeeRate.setMinimum()
        // Check wallet network
        return transferWallet.checkWalletNetwork(btcNetworkConfigProvider.getConfig()).flatMap {
            btcChangeAddressProvider.getAllChangeAddresses()
        }.map { changeAddresses ->
            if (changeAddresses.isEmpty()) {
                throw IllegalStateException("No change addresses were generated")
            }
        }.flatMap {
            initBtcBlockChain()
        }.flatMap {
            initWithdrawalTransferListener()
        }
    }

    /**
     * Initiates listener that listens to withdrawal events in Iroha
     * @return result of initiation process
     */
    private fun initWithdrawalTransferListener(
    ): Result<Unit, Exception> {
        return Result.of {
            irohaChainListener.getBlockObservable().map { observable ->
                observable.doOnError { ex ->
                    notHealthy()
                    logger.error("Error on transfer events subscription", ex)
                }
            }
            Unit
        }
    }

    /**
     * Handles Iroha blocks
     * @param block - Iroha block
     */
    private fun handleIrohaBlock(block: BlockOuterClass.Block) {
        // Handle transfer commands
        getTransferCommands(block).forEach { command ->
            newTransferHandler.handleTransferCommand(
                command.transferAsset,
                block.blockV1.payload.createdTime
            )
        }
        // Handle signature appearance commands
        getSetDetailCommands(block).filter { command -> isNewWithdrawalSignature(command) }
            .forEach { command ->
                newSignatureEventHandler.handleNewSignatureCommand(
                    command.setAccountDetail,
                    peerGroup
                ) { transferWallet.saveToFile(File(btcWithdrawalConfig.btcTransfersWalletPath)) }
            }
        // Handle 'set new consensus' events
        getSetDetailCommands(block).filter { command -> isNewConsensus(command) }
            .forEach { command ->
                newConsensusDataHandler.handleNewConsensusCommand(command.setAccountDetail)
            }
        // Handle newly registered Bitcoin addresses. We need it to update transferWallet object.
        getSetDetailCommands(block).forEach { command ->
            newBtcClientRegistrationHandler.handleNewClientCommand(command, transferWallet)
        }

        // Handle newly generated Bitcoin change addresses. We need it to update transferWallet object.
        getSetDetailCommands(block).filter { command ->
            command.hasSetAccountDetail() &&
                    command.setAccountDetail.accountId == btcWithdrawalConfig.changeAddressesStorageAccount
        }.forEach { command ->
            newChangeAddressHandler.handleNewChangeAddress(command.setAccountDetail)
        }
    }

    // Calls apply and then acknowledges it safely
    private fun safeApplyAck(apply: () -> Unit, ack: () -> Unit) {
        try {
            apply()
        } finally {
            ack()
        }
    }

    /**
     * Starts Bitcoin block chain download process
     */
    private fun initBtcBlockChain(): Result<PeerGroup, Exception> {
        //Enables short log format for Bitcoin events
        BriefLogFormatter.init()
        return Result.of {
            startChainDownload(peerGroup)
            addPeerConnectionStatusListener(peerGroup, ::notHealthy, ::cured)
            peerGroup
        }
    }

    private fun isNewWithdrawalSignature(command: Commands.Command) =
        command.hasSetAccountDetail() && command.setAccountDetail.accountId.endsWith("@$BTC_SIGN_COLLECT_DOMAIN")

    private fun isNewConsensus(command: Commands.Command) =
        command.hasSetAccountDetail() && command.setAccountDetail.accountId.endsWith("@$BTC_CONSENSUS_DOMAIN")

    override fun close() {
        logger.info { "Closing Bitcoin withdrawal service" }
        irohaChainListener.close()
        peerGroup.stop()
    }

    /**
     * Logger
     */
    companion object : KLogging()
}