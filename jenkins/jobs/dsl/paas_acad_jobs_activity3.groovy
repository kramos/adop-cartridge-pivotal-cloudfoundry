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
]

// Jobs
def pullSCM = PaaSAcademyCartridge.getBuildFromSCMJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/Pull_Spring_Music', variables),
    variables + [
        'artifactDefaultValue': 'spring-music',
        'triggerDownstreamJob': projectFolderName + '/Build_Heroku_CLI_Image'
    ]
)

def buildHerokuUtilityJob = PaaSAcademyCartridge.getBuildHerokuUtilityImageJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/Build_Heroku_CLI_Image', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/Pull_Spring_Music',
        'triggerDownstreamJob': projectFolderName + '/SM_Heroku_Deploy'
    ]
)

def buildAppJob = PaaSAcademyCartridge.getHerokuCliJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/SM_Heroku_Deploy', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/Pull_Spring_Music',
        'nextCopyArtifactsFromBuild': '${B}',
        'triggerDownstreamJob': projectFolderName + '/Heroku_Experiments',
        'jobDescription': 'This job deploys the application to Heroku',
        'jobCommand': '''heroku create testapp${RANDOM}; \\
                        |heroku addons:create heroku-postgresql:hobby-dev; \\
                        |git push heroku HEAD:master'''
    ], 'manual'
)

def herokuExperimentsJob = PaaSAcademyCartridge.getHerokuCliJob(
    PaaSAcademyCartridge.baseCartridgeJob(this, projectFolderName + '/Heroku_Experiments', variables),
    variables + [
        'copyArtifactsFromJob': projectFolderName + '/Pull_Spring_Music',
        'nextCopyArtifactsFromBuild': '${B}',
        'triggerDownstreamJob': projectFolderName + '/Heroku_Experiments',
        'jobDescription': 'This job can be manually altered to run / experiment with Heroku commands.   It relies on Jenkins credentials being available called "heroku-credentials" for the PaaS account you want to deploy to.',
        'jobCommand': '''heroku version'''
    ], 'manual'
)

// Views
def rolePipelineView = PaaSAcademyCartridge.basePipelineView(
    this,
    projectFolderName + '/Spring_Music_To_Heroku',
    projectFolderName + '/Pull_Spring_Music',
    'Spring Music to Heroku' 
)

