import com.sap.cloud.sdk.s4hana.pipeline.QualityCheck
import com.sap.cloud.sdk.s4hana.pipeline.ReportAggregator
import com.sap.piper.ConfigurationLoader

def call(Map parameters = [:]) {
    def stageName = 'staticCodeChecks'
    def script = parameters.script
    runAsStage(stageName: stageName, script: script) {
        Map configuration = ConfigurationLoader.stageConfiguration(script, stageName)

        runOverModules(script: script, moduleType: "java") { basePath ->
            checkPmd script: script, excludes: configuration.pmdExcludes, basePath: basePath
            ReportAggregator.instance.reportStaticCodeExecution(QualityCheck.PmdCheck)

            checkFindbugs script: script, excludeFilterFile: configuration.findbugsExcludesFile, basePath: basePath
            ReportAggregator.instance.reportStaticCodeExecution(QualityCheck.FindbugsCheck)
        }
    }
}
