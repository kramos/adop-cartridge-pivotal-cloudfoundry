package base

/**
 * Re-usable goodness for building Cloud Foundry jobs
 */
class CloudFoundryCartridge {

    /**
     * Creates useful PaaS related jobs
     *
     * @dslFactory base DSL file
     * @jobName Jenkins job name
     * @variables reuired variables
     */
    static baseCartridgeJob(dslFactory, jobName, variables) {
        dslFactory.freeStyleJob(jobName) {
            label(variables.buildSlave)
            environmentVariables {
                env('WORKSPACE_NAME', variables.workspaceFolderName)
                env('PROJECT_NAME', variables.projectFolderName)
                env('ABSOLUTE_JENKINS_HOME', variables.absoluteJenkinsHome)
                env('ABSOLUTE_JENKINS_SLAVE_HOME', variables.absoluteJenkinsSlaveHome)
                env('ABSOLUTE_WORKSPACE', variables.absoluteWorkspace)
            }
            wrappers {
                sshAgent(variables.sshAgentName)
                timestamps()
                maskPasswords()
                colorizeOutput()
                preBuildCleanup()
                injectPasswords {
                    injectGlobalPasswords(false)
                    maskPasswordParameters(true)
                }
            }
            logRotator {
                numToKeep(variables.logRotatorNum)
                artifactNumToKeep(variables.logRotatorArtifactNum)
                daysToKeep(variables.logRotatorDays)
                artifactDaysToKeep(variables.logRotatorArtifactDays)
            }
        }
    }

    /**
     * Creates a pipeline view
     *
     * @dslFactory base DSL file
     * @viewName Name of view
     * @jobName Name of first job in pipeline
     * @viewTitle Title of view
     */
    static basePipelineView(dslFactory, viewName, jobName, viewTitle) {
        dslFactory.buildPipelineView(viewName) {
            title(viewTitle)
            displayedBuilds(5)
            refreshFrequency(5)
            selectedJob(jobName)
            showPipelineParameters()
            showPipelineDefinitionHeader()
        }
    }

    /**
     * Creates the get from SCM job
     *
     * @job a base job that will be extended
     * @variables variables required configuration
     */
    static void getBuildFromSCMJob(def job, variables) {
        job.with {
            description('This job downloads a ' + variables.artifactName.toLowerCase() + '. Also job have Gerrit trigger with regexp configuration to capture events "' + variables.artifactName.toLowerCase() + '" in the repository name.')
            parameters {
                stringParam("REPO", variables.artifactDefaultValue, 'Name of the ' + variables.artifactName.toLowerCase() + ' you want to load')
            }
            environmentVariables {
                env('WORKSPACE_NAME', variables.workspaceFolderName)
                env('PROJECT_NAME', variables.projectFolderName)
                groovy('''
        if (!binding.variables.containsKey('GERRIT_PROJECT')) {
            return [GERRIT_PROJECT: "''' + variables.projectFolderName + '''/${REPO}"]
        }'''.stripMargin())
            }
            scm {
                git {
                    remote {
                        url(variables.gitUrl)
                        credentials(variables.gitCredentials)
                    }
                    branch('*/' + variables.gitBranch)
                }
            }
            configure { node ->
                node / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' / 'projectNameList' {
                    'string' '*'
                }
            }
            steps {
                shell('''#!/bin/bash -xe
                    |git_data=$(git --git-dir "${WORKSPACE}/.git" log -1 --pretty="format:%an<br/>%s%b")
                    |echo "GIT_LOG_DATA=${git_data}" > git_log_data.properties
                    '''.stripMargin())
                environmentVariables {
                    propertiesFile('git_log_data.properties')
                }
            }
            steps {
                systemGroovyCommand('''
                                |import hudson.model.*;
                                |import hudson.util.*;
                                |
                                |// Get current build number
                                |def currentBuildNum = build.getEnvironment(listener).get('BUILD_NUMBER')
                                |println "Build Number: " + currentBuildNum
                                |
                                |// Get Git Data
                                |def gitData = build.getEnvironment(listener).get('GIT_LOG_DATA')
                                |println "Git Data: " + gitData;
                                |
                                |def currentBuild = Thread.currentThread().executable;
                                |def oldParams = currentBuild.getAction(ParametersAction.class)
                                |
                                |// Update the param
                                |def params = [ new StringParameterValue("T",gitData), new StringParameterValue("B",currentBuildNum) ]
                                |
                                |// Remove old params - Plugins inject variables!
                                |currentBuild.actions.remove(oldParams)
                                |currentBuild.addAction(new ParametersAction(params));
                                '''.stripMargin())
            }
            triggers {
                gerrit {
                    events {
                        refUpdated()
                    }
                    project('reg_exp:' + variables.gerritTriggerRegExp, 'plain:master')
                    configure { node ->
                        node / serverName('ADOP Gerrit')
                    }
                }
            }
            publishers {
                archiveArtifacts {
                    pattern('**/*')
                }
                downstreamParameterized {
                    trigger(variables.triggerDownstreamJob) {
                        condition('UNSTABLE_OR_BETTER')
                        parameters {
                            predefinedProp('B', variables.nextCopyArtifactsFromBuild)
                        }
                    }
                }
            }
        }
    }


    /**
     * Creates a generic job for running SonarQube 
     *
     * @job a base job that will be extended
     * @variables variables required configuration
     */
    static getSonarQubeJob(def job, variables) {
        job.with {
            description(variables.jobDescription)
            parameters {
                stringParam('B', '', 'Parent build job number')
            }
            steps {
                copyArtifacts(variables.copyArtifactsFromJob) {
                    buildSelector {
                        buildNumber('${B}')
                    }
                }
            }

            configure { myProject ->
                myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
                    project('sonar-project.properties')
                    properties('''sonar.projectKey=''' + variables.projectNameKey + '''
                                  sonar.projectName=${PROJECT_NAME}
                                  sonar.projectVersion=1.0.${B}
                                  sonar.sources=src/main/java
                                  sonar.language=java
                                  sonar.sourceEncoding=UTF-8
                                  sonar.scm.enabled=false''')
                    javaOpts()
                    jdk('(Inherit From Job)')
                    task()
                }
            }

            publishers {
                archiveArtifacts {
                    pattern('**/*')
                }
                downstreamParameterized {
                    trigger(variables.triggerDownstreamJob) {
                        condition('UNSTABLE_OR_BETTER')
                        parameters {
                            predefinedProp('B', variables.nextCopyArtifactsFromBuild)
                        }
                    }
                }
            }
        }

        return job
    }

    /**
     * Creates a generic job for using Gradle
     *
     * @job a base job that will be extended
     * @variables variables required configuration
     */
    static getGradleJob(def job, variables) {
        job.with {
            description(variables.jobDescription)
            parameters {
                stringParam('B', '', 'Parent build job number')
            }
            steps {
                copyArtifacts(variables.copyArtifactsFromJob) {
                    buildSelector {
                        buildNumber('${B}')
                    }
                }
                shell('''#!/bin/bash -xe
                        |echo '''.stripMargin() + variables.jobDescription + '''
                        |docker run --rm \\
                        |-v ${ABSOLUTE_WORKSPACE}:/jworkspacedir \\
                        |-v /var/run/docker.sock:/var/run/docker.sock \\
                        |-w /jworkspacedir \\
                        |'''.stripMargin() + variables.gradleImage + '''
                        |bash -c " \\
                        |set -xe;  \\
                        |'''.stripMargin() + variables.jobCommand.stripMargin().stripMargin() + '"')
            }

            publishers {
                archiveArtifacts {
                    pattern('**/*')
                }
                downstreamParameterized {
                    trigger(variables.triggerDownstreamJob) {
                        condition('UNSTABLE_OR_BETTER')
                        parameters {
                            predefinedProp('B', variables.nextCopyArtifactsFromBuild)
                        }
                    }
                }
            }
        }

        return job
    }

    /**
     * Creates a generic job using CF CLI
     *
     * @job a base job that will be extended
     * @variables variables required configuration
     */
    static getCfCliJob(def job, variables) {
        job.with {
            description(variables.jobDescription)
            parameters {
                stringParam('B', '', 'Parent build job number')
                choiceParam('CF_PROVIDER_LIB', variables.cloudFoundryLib.tokenize(), 'Name of the API library for the Cloud Foundry provider.')
                credentialsParam("PAAS_LOGIN"){
                    type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
                    required()
                    defaultValue('cf-credentials')
                    description('PaaS Provider username and password. Please make sure the credentials are added with ID "cf-credentials"')
                }
            }
            wrappers {
                credentialsBinding {
                    usernamePassword("PAAS_USERNAME", "PAAS_PASSWORD", '${PAAS_LOGIN}')
                }
            }
            steps {
                copyArtifacts(variables.copyArtifactsFromJob) {
                    buildSelector {
                        buildNumber('${B}')
                    }
                }
                shell('''#!/bin/bash -xe
                        |echo '''.stripMargin() + variables.jobDescription + '''
                        |docker run --rm \\
                        |-v ${ABSOLUTE_WORKSPACE}:/jworkspacedir \\
                        |-v /var/run/docker.sock:/var/run/docker.sock \\
                        |-w /jworkspacedir \\
                        |'''.stripMargin() + variables.cfCliImage + '''
                        |bash -c " \\
                        |set -xe;  \\
                        |'''.stripMargin() + variables.jobCommand.stripMargin() + '''"
                        |echo
                        |echo
                        |echo +++++++++++++++++++++++++++++++++++++++++++++
                        |echo If you just created things in a public PaaS
                        |echo You must perform your own clean up to reduce / avoid costs
                        |echo This is most easily done from the web console dashboard
                        |echo +++++++++++++++++++++++++++++++++++++++++++++'''.stripMargin())
            }

            publishers {
                archiveArtifacts {
                    pattern('**/*')
                }
                downstreamParameterized {
                    trigger(variables.triggerDownstreamJob) {
                        condition('UNSTABLE_OR_BETTER')
                        parameters {
                            predefinedProp('B', variables.nextCopyArtifactsFromBuild)
                        }
                    }
                }
            }
        }

        return job
    }

}
