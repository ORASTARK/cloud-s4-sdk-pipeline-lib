import com.sap.cloud.sdk.s4hana.pipeline.DownloadCacheUtils
import com.sap.piper.BashUtils

import static com.sap.cloud.sdk.s4hana.pipeline.EnvironmentAssertionUtils.assertPluginIsActive

def call(Map parameters = [:]) {

    handleStepErrors(stepName: 'checkUi5BestPractices', stepParameters: parameters) {
        // Might be written both upper and lowercase
        String[] ui5ComponentsUpperCase = []
        String[] ui5ComponentsLowerCase = []

        try {
            ui5ComponentsUpperCase = findFiles(glob: '**/Component.js')
            ui5ComponentsLowerCase = findFiles(glob: '**/component.js')
        } catch (IOException ioe) {
            error "An error occurred when looking for UI5 components.\n"
        }

        String[] ui5Components = ui5ComponentsUpperCase.plus(ui5ComponentsLowerCase)

        if (ui5Components.size() > 0) {
            assertPluginIsActive('warnings-ng')

            echo "UI5 Best Practices lint discovered ${ui5Components.size()} UI5 components for scan: ${ui5Components.join(", ")}."

            final script = parameters.script
            final Map configuration = parameters.configuration

            def dockerOptions = []
            DownloadCacheUtils.appendDownloadCacheNetworkOption(script, dockerOptions)

            String checkUi5BestPracticesScript = libraryResource "check-ui5-best-practices/run-ui5-lint.js"
            writeFile file: "run-ui5-lint.js", text: checkUi5BestPracticesScript

            String packageJson = libraryResource "check-ui5-best-practices/package.json"
            writeFile file: "package.json", text: packageJson

            executeNpm(script: script, dockerOptions: dockerOptions) {
                sh 'npm config set @sap:registry https://npm.sap.com'
                sh 'npm install'

                boolean es6Enabled = configuration?.ui5BestPractices?.enableES6
                boolean es2017Enabled = configuration?.ui5BestPractices?.enableES2017
                boolean es2020Enabled = configuration?.ui5BestPractices?.enableES2020

                String highestConfiguredLanguageLevel = null
                if (es6Enabled) {
                    highestConfiguredLanguageLevel = 'es6'
                }
                if (es2017Enabled) {
                    highestConfiguredLanguageLevel = 'es2017'
                }
                if (es2020Enabled) {
                    highestConfiguredLanguageLevel = 'es2020'
                }

                if (highestConfiguredLanguageLevel) {
                    Map basicDefaultConfig = readJSON file: 'node_modules/@sap/di.code-validation.js/src/defaultConfig/basicDefaultConfig/.eslintrc.json'
                    basicDefaultConfig['env'][highestConfiguredLanguageLevel] = true
                    writeJSON file: 'node_modules/@sap/di.code-validation.js/src/defaultConfig/basicDefaultConfig/.eslintrc.json', json: basicDefaultConfig

                    Map fioriCustomRules = readJSON file: 'node_modules/@sap/di.code-validation.js/src/defaultConfig/fioriCustomRules/.eslintrc.json'
                    fioriCustomRules['env'][highestConfiguredLanguageLevel] = true
                    writeJSON file: 'node_modules/@sap/di.code-validation.js/src/defaultConfig/fioriCustomRules/.eslintrc.json', json: fioriCustomRules
                }

                ui5Components.each { componentJsFile ->
                    sh "node run-ui5-lint.js ${BashUtils.quoteAndEscape(componentJsFile)} "
                }
            }

            executeWithLockedCurrentBuildResult(script: script, errorStatus: 'FAILURE', errorHandler: script.buildFailureReason.setFailureReason, errorHandlerParameter: 'checkUi5BestPractices', errorMessage: "SAPUI5 lint failed. Please fix the reported violations.") {
                recordDiscoveredIssues(configuration?.ui5BestPractices?.failThreshold)
            }
        } else {
            echo "No UI5 components found, skipping UI5 Best Practices lint."
        }
    }
}

private void recordDiscoveredIssues(Map failThreshold) {
    int failedError = (failThreshold?.error != null) ? failThreshold.error : Integer.MAX_VALUE
    int failedNormal = (failThreshold?.warning != null) ? failThreshold.warning : Integer.MAX_VALUE
    int failedLow = (failThreshold?.info != null) ? failThreshold.info : Integer.MAX_VALUE

    recordIssues blameDisabled: true,
        enabledForFailure: true,
        aggregatingResults: false,
        tool: checkStyle(id: "ui5lint", name: "SAPUI5 Best Practices", pattern: '*ui5lint.xml'),
        qualityGates: [
            [threshold: failedError, type: 'TOTAL_ERROR', unstable: false],
            [threshold: failedNormal, type: 'TOTAL_NORMAL', unstable: false],
            [threshold: failedLow, type: 'TOTAL_LOW', unstable: false],
        ]

    sh 'rm -f *.ui5lint.*'
}
