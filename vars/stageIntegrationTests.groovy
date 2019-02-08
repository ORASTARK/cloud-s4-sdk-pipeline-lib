import com.sap.cloud.sdk.s4hana.pipeline.PathUtils
import com.sap.cloud.sdk.s4hana.pipeline.QualityCheck
import com.sap.cloud.sdk.s4hana.pipeline.ReportAggregator
import com.sap.piper.ConfigurationLoader
import com.sap.piper.ConfigurationMerger

def call(Map parameters = [:]) {
    def stageName = 'integrationTests'
    def script = parameters.script

    runAsStage(stageName: stageName, script: script) {
        runOverModules(script: script, moduleType: "java") { String basePath ->
            executeIntegrationTest(script, basePath, stageName)
        }
    }
}

private void executeIntegrationTest(def script, String basePath, String stageName) {
    final Map stageConfiguration = ConfigurationLoader.stageConfiguration(script, stageName)
    final Map stageDefaults = ConfigurationLoader.defaultStageConfiguration(script, stageName)
    Set stageConfigurationKeys = [
        'retry',
        'credentials',
        'forkCount'
    ]
    Map configuration = ConfigurationMerger.merge(stageConfiguration, stageConfigurationKeys, stageDefaults)

    try {
        try {
            if (configuration.credentials != null) {
                dir("$basePath/integration-tests/src/test/resources") {
                    writeCredentials(configuration.credentials)
                }
            }

            int count = 0
            try {
                count = configuration.retry.toInteger()
            }
            catch (Exception e) {
                error("retry: ${configuration.retry} must be an integer")
            }
            def forkCount = configuration.forkCount

            //Remove ./ in path as it does not work with surefire 3.0.0-M1
            String pomPath = PathUtils.normalize(basePath, "integration-tests/pom.xml")

            mavenExecute(
                script: script,
                flags: "--batch-mode",
                pomPath: pomPath,
                m2Path: s4SdkGlobals.m2Directory,
                goals: "org.jacoco:jacoco-maven-plugin:prepare-agent test",
                dockerImage: configuration.dockerImage,
                defines: "-Dsurefire.rerunFailingTestsCount=$count -Dsurefire.forkCount=$forkCount"
            )
            ReportAggregator.instance.reportTestExecution(QualityCheck.IntegrationTests)

        } catch (Exception e) {
            executeWithLockedCurrentBuildResult(
                script: script,
                errorStatus: 'FAILURE',
                errorHandler: script.buildFailureReason.setFailureReason,
                errorHandlerParameter: 'Backend Integration Tests',
                errorMessage: "Please examine Backend Integration Tests report."
            ) {
                script.currentBuild.result = 'FAILURE'
            }
            throw e
        } finally {
            String testResultPattern = "${basePath}/integration-tests/target/surefire-reports/TEST-*.xml".replaceAll("//", "/")

            if(testResultPattern.startsWith("./")){
                testResultPattern = testResultPattern.substring(2)
            }

            junit allowEmptyResults: true, testResults: testResultPattern
        }
    } finally {
        dir("$basePath/integration-tests/src/test/resources") {
            deleteCredentials()
        }
    }
    copyExecFile execFiles: [
        "$basePath/integration-tests/target/jacoco.exec",
        "$basePath/integration-tests/target/coverage-reports/jacoco.exec",
        "$basePath/integration-tests/target/coverage-reports/jacoco-ut.exec"
    ], targetFolder:basePath, targetFile: 'integration-tests.exec'

    if (script.commonPipelineEnvironment.configuration.isMta) {
        sh("mkdir -p ${s4SdkGlobals.reportsDirectory}/service_audits/; cp $basePath/s4hana_pipeline/reports/service_audits/*.log ${s4SdkGlobals.reportsDirectory}/service_audits/ || echo 'Warning: No audit logs found'")
    }
}
