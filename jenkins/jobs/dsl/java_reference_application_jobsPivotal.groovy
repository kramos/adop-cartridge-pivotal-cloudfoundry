import base.PaaSAcademyCartridge

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def variables = [
    gitUrl                  : 'ssh://jenkins@gerrit:29418/${GERRIT_PROJECT}',
    gitBranch               : 'master',
    gitCredentials          : 'adop-jenkins-master',
    gerritTriggerRegExp     : (projectFolderName + '/spring-music').replaceAll("/", "\\\\/"),
    projectNameKey          : (projectFolderName.toLowerCase().replace("/", "-")),
    buildSlave              : 'docker',
    artifactName            : 'spring-music',
    sshAgentName            : 'adop-jenkins-master',
    logRotatorNum           : 10,
    logRotatorArtifactNum   : 3,
    logRotatorDays          : -1,
    logRotatorArtifactDays  : -1,
    projectFolderName       : projectFolderName,
    workspaceFolderName     : workspaceFolderName,
    absoluteJenkinsHome     : '/var/lib/docker/volumes/jenkins_home/_data',
    absoluteJenkinsSlaveHome: '/var/lib/docker/volumes/jenkins_slave_home/_data',
    absoluteWorkspace       : '${ABSOLUTE_JENKINS_SLAVE_HOME}/${JOB_NAME}/',
    cfCliImage              : 'kramos/cfcli',
    cloudFoundryLib:        :  'api.run.pivotal.io\napi.ng.bluemix.net'
]

// Jobs
def pullSCM = PaaSAcademyCartridge.getBuildFromSCMJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/Get_Spring_Music', variables),
    variables + [
        'artifactDefaultValue': 'spring-music',
        'triggerDownstreamJob': projectFolderName + '/Build_CF_CLI_Image'
    ]
)

def buildCFUtilityJob = PaaSAcademyCartridge.getBuildCfUtilityImageJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/Build_CF_CLI_Image', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/Get_Spring_Music',
        'triggerDownstreamJob': projectFolderName + '/SM_Build'
    ]
)


def buildAppJob = PaaSAcademyCartridge.getCfCliJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/SM_Build', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/Get_Spring_Music',
        'nextCopyArtifactsFromBuild': '${BUILD_NUMBER}',
        'triggerDownstreamJob': projectFolderName + '/SM_Unit_Tests',
        'jobDescription': 'This job builds the application',
        'jobCommand': './gradlew assemble'
    ]
)


def unitTestJob = PaaSAcademyCartridge.getCfCliJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/SM_Unit_Tests', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/SM_Build',
        'nextCopyArtifactsFromBuild': '${B}',
        'triggerDownstreamJob': projectFolderName + '/SM_Code_Analysis',
        'jobDescription': 'This job runs unit tests on our Spring Music application',
        'jobCommand': './gradlew test'
    ]
)

def codeAnalysisJob = PaaSAcademyCartridge.getSonarQubeJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/SM_Code_Analysis', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/SM_Build',
        'nextCopyArtifactsFromBuild': '${B}',
        'triggerDownstreamJob': projectFolderName + '/SM_CF_Deploy',
        'jobDescription': 'Static code quality analysis of our springMusic application using SonarQube.',
    ]
)


def cfDeployJob = PaaSAcademyCartridge.getCfCliJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/SM_CF_Deploy', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/SM_Build',
        'nextCopyArtifactsFromBuild': '${B}',
        'triggerDownstreamJob': projectFolderName + '/Reference_Application_Regression_Tests',
        'jobDescription': 'This job deploys the springMusic application to a Pivotal Cloud Foundry environment.  The job will fail unless Jenkins credentials called "pcf-credentials" are available for the PaaS account you want to deploy to.',
        'jobCommand': '''cf api --skip-ssl-validation $CF_PROVIDER_LIB; \\
                        |cf login -u \$PAAS_USERNAME -p \$PAAS_PASSWORD; \\
                        |cf push testapp -m 512MB -b java_buildpack -p build/libs/spring-music.jar --no-start;  \\
                        |cf create-service cleardb spark tododbname;  \\
                        |cf bind-service testapp tododbname;  \\
                        |cf push testapp -m 512MB -b java_buildpack -p build/libs/spring-music.jar'''
    ]
)

// Views
def rolePipelineView = PaaSAcademyCartridge.basePipelineView(
    this,
    projectFolderName + '/Spring_Music_To_Cloud_Foundry',
    projectFolderName + '/Get_Spring_Music',
    'Spring Music To Pivotal and IBM BlueMix Cloud Foundry)'
)

