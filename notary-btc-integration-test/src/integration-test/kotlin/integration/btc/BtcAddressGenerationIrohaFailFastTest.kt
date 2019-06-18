/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import integration.helper.ContainerHelper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationIrohaFailFastTest {

    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-address-generation/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-address-generation/build/docker/"
    // Create address generation container
    private val addressGenerationContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount Bitcoin wallet
        addressGenerationContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Start Iroha
        containerHelper.irohaContainer.start()
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_HOSTNAME",
            containerHelper.irohaContainer.toriiAddress.host
        )
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_PORT",
            containerHelper.irohaContainer.toriiAddress.port.toString()
        )
        // Start service
        addressGenerationContainer.start()
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        addressGenerationContainer.stop()
    }

    /**
     * @given address generation and Iroha services being started
     * @when Iroha dies
     * @then address generation dies as well
     */
    @Test
    fun testFailFast() {
        // Let service work a little
        Thread.sleep(15_000)
        assertTrue(containerHelper.isServiceHealthy(addressGenerationContainer))
        // Kill Iroha
        containerHelper.irohaContainer.stop()
        // Wait a little
        Thread.sleep(5_000)
        // Check that the service is dead
        assertTrue(containerHelper.isServiceDead(addressGenerationContainer))
    }
}