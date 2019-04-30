
pipeline {
  environment {
    DOCKER_NETWORK = ''
  }
  options {
    skipDefaultCheckout()
    buildDiscarder(logRotator(numToKeepStr: '20'))
    timestamps()
  }
  agent any
  stages {
    stage('Stop same job builds') {
      agent { label 'master' }
      steps {
        script {
          def scmVars = checkout scm
          // need this for develop->master PR cases
          // CHANGE_BRANCH is not defined if this is a branch build
          try {
            scmVars.CHANGE_BRANCH_LOCAL = scmVars.CHANGE_BRANCH
          }
          catch (MissingPropertyException e) {
          }
          if (scmVars.GIT_LOCAL_BRANCH != "develop" && scmVars.CHANGE_BRANCH_LOCAL != "develop") {
            def builds = load ".jenkinsci/cancel-builds-same-job.groovy"
            builds.cancelSameJobBuilds()
          }
        }
      }
    }
    stage('Tests') {
      agent { label 'd3-build-agent' }
      steps {
        script {
          def scmVars = checkout scm
          tmp = docker.image("openjdk:8-jdk")
          env.WORKSPACE = pwd()

          DOCKER_NETWORK = "${scmVars.CHANGE_ID}-${scmVars.GIT_COMMIT}-${BUILD_NUMBER}"
          writeFile file: ".env", text: "SUBNET=${DOCKER_NETWORK}"
          withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {
            sh "docker login nexus.iroha.tech:19002 -u ${login} -p '${password}'"

            sh "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml pull"
            sh(returnStdout: true, script: "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml up --build -d")
            sh "docker cp d3-btc-node0-${DOCKER_NETWORK}:/usr/bin/bitcoin-cli deploy/bitcoin/"
          }

          iC = docker.image("gradle:4.10.2-jdk8-slim")
          iC.inside("--network='d3-${DOCKER_NETWORK}' -e JVM_OPTS='-Xmx3200m' -e TERM='dumb'") {
            sh "ln -s deploy/bitcoin/bitcoin-cli /usr/bin/bitcoin-cli"
            sh "gradle dependencies"
            sh "gradle test --info"
            sh "gradle compileIntegrationTestKotlin --info"
            sh "gradle integrationTest --info"
          }
        }
      }
      post {
        cleanup {
          sh "mkdir -p build-logs"
          sh """
            while read -r LINE; do \
              docker logs \$(echo \$LINE | cut -d ' ' -f1) | gzip -6 > build-logs/\$(echo \$LINE | cut -d ' ' -f2).log.gz; \
            done < <(docker ps --filter "network=d3-${DOCKER_NETWORK}" --format "{{.ID}} {{.Names}}")
          """
          
          sh "tar -zcvf build-logs/notaryBtcIntegrationTest.gz -C notary-btc-integration-test/build/reports/tests integrationTest || true"
          sh "tar -zcvf build-logs/dokka.gz -C build/reports dokka || true"
          archiveArtifacts artifacts: 'build-logs/*.gz'
          sh "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml down"
          cleanWs()
        }
      }
    }

    stage('Build and push docker images') {
      agent { label 'd3-build-agent' }
      steps {
        script {
          def scmVars = checkout scm
          if (env.BRANCH_NAME ==~ /(master|develop|reserved)/) {
            withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {
              sh "docker login nexus.iroha.tech:19002 -u ${login} -p '${password}'"

              TAG = env.BRANCH_NAME
              sh "rm build/libs/notary-1.0-SNAPSHOT-all.jar || true"
              iC = docker.image("gradle:4.10.2-jdk8-slim")
              iC.inside("-e JVM_OPTS='-Xmx3200m' -e TERM='dumb'") {

                sh "gradle btc-address-generation:shadowJar"
                sh "gradle btc-registration:shadowJar"
                sh "gradle btc-dw-bridge:shadowJar"
              }

              btcAddressGeneration = docker.build("nexus.iroha.tech:19002/${login}/btc-address-generation:${TAG}", "-f docker/btc-address-generation.dockerfile .")
              btcRegistration = docker.build("nexus.iroha.tech:19002/${login}/btc-registration:${TAG}", "-f docker/btc-registration.dockerfile .")
              btcDwBridge = docker.build("nexus.iroha.tech:19002/${login}/btc-dw-bridge:${TAG}", "-f docker/btc-dw-bridge.dockerfile .")

              btcAddressGeneration.push("${TAG}")
              btcRegistration.push("${TAG}")
              btcDwBridge.push("${TAG}")
            }
          }
        }
      }
    }
  }
}