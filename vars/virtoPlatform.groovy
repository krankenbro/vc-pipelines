#!groovy
import groovy.json.*
import groovy.util.*
import jobs.scripts.*


def call(body){

	projectType = "NET4"

	def config = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	body()
	solution = config.solution
	projectType = config.projectType
	def swaggerTargetPlatformDll = 'VirtoCommerce.Platform.Web.dll'
	node () {
		if(solution == null) {
			solution = "VirtoCommerce.Platform.sln"
		}
		if(projectType == null) {
			projectType = "NET4"
		}

		try {
			stage ('Checkout') {
				timestamps {
					checkout scm;
				}
			}
		
			stage ('Build & Analyze') {
				timestamps {
					if(projectType == "NETCORE2") { //storefront
						Packaging.startAnalyzer(this)
						bat "\"${tool 'DefaultMSBuild'}\\msbuild.exe\" \"${solution}\" /p:Configuration=Debug /p:Platform=\"Any CPU\" /t:restore /t:rebuild /m"
					}
					else  { //platform
						bat "nuget restore ${solution}"
						Packaging.startAnalyzer(this)
						bat "\"${tool 'DefaultMSBuild'}\\msbuild.exe\" \"${solution}\" /p:Configuration=Debug /p:Platform=\"Any CPU\" /t:rebuild /m"
					}
				}
			}
			def tests = Utilities.getTestDlls(this)
			if(projectType == "NETCORE2" && tests.size() < 1) {
				tests = findFiles(glob: '**\\bin\\Debug\\*\\*Tests.dll')
			}
			if(tests.size() > 0)
			{
				stage('Tests') {
					timestamps { 
						String paths = ""
						String traits = "-trait \"category=ci\" -trait \"category=Unit\""
						String resultsFileName = "xUnit.UnitTests.xml"
						for(int i = 0; i < tests.size(); i++)
						{
							def test = tests[i]
							paths += "\"$test.path\" "
						}
						if(projectType == "NETCORE2") {
							bat "dotnet vstest ${paths} --TestCaseFilter:\"Category=Unit\""
						}
						else {
							bat "\"${env.XUnit}\\xunit.console.exe\" ${paths} -xml \"${resultsFileName}\" ${traits} -parallel none"
						}
					}
				}
			}
			stage('Submit Analyze') {
				timestamps {
					Packaging.endAnalyzer(this)
				}
			}

			// No need to occupy a node
			stage("Quality Gate"){
				timestamps {
					Packaging.checkAnalyzerGate(this)
				}
			}

			if(projectType == 'NET4') {
				stage('Swagger Validation') {
					timestamps {
						String swagPaths = ""
						def swagDlls = findFiles(glob: "VirtoCommerce.Platform.Web\\bin\\${swaggerTargetPlatformDll}")
						if(swagDlls.size() > 0)
						{
							for(swagDll in swagDlls){
								swagPaths += "\"$swagDll.path\""
							}
						}
						bat "nswag webapi2swagger /assembly:${swagPaths} /output:${env.WORKSPACE}\\swagger.json"
						bat "swagger-cli validate ${env.WORKSPACE}\\swagger.json"
					}
				}
			}
		}
		catch(Throwable e) {
			currentBuild.result = 'FAILURE'
			def log = currentBuild.rawBuild.getLog(100)
			def failedStageLog = Utilities.getFailedStage(log)
			Utilities.sendMail this, "${currentBuild.currentResult}", "${e.getMessage()}\n${e.getCause()}\n${failedStageLog}"
			throw e
		}
		finally {
			if(currentBuild.result != 'FAILURE') {
				Utilities.sendMail(this, "${currentBuild.currentResult}")
			}
		}
	}
}


// module script
def hard(body) {
	// evaluate the body block, and collect configuration into the object
	def config = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	body()
    
	node
	{
		// configuration parameters
		def hmacAppId = env.HMAC_APP_ID
		def hmacSecret = env.HMAC_SECRET
		def solution = config.solution
		projectType = config.projectType
		
		def webProject = 'VirtoCommerce.Platform.Web\\VirtoCommerce.Platform.Web.csproj'
		def zipArtifact = 'VirtoCommerce.Platform'
		def websiteDir = 'VirtoCommerce.Platform.Web'
		def deployScript = 'VC-Platform2AzureDev.ps1'
		def dockerTag = env.BRANCH_NAME
		def buildOrder = Utilities.getNextBuildOrder(this)
		if (env.BRANCH_NAME == 'master') {
			deployScript = 'VC-Platform2AzureQA.ps1'
			dockerTag = "latest"
		}
		
		if(projectType == null)
		{
			projectType = "NET4"
		}

		if(solution == null)
		{
			 solution = 'VirtoCommerce.Platform.sln'
		}
		else
		{
			websiteDir = 'VirtoCommerce.Storefront'
			webProject = 'VirtoCommerce.Storefront\\VirtoCommerce.Storefront.csproj'
			zipArtifact = 'VirtoCommerce.StoreFront'
			deployScript = 'VC-Storefront2AzureDev.ps1'
			if (env.BRANCH_NAME == 'master') {
				deployScript = 'VC-Storefront2AzureQA.ps1'
			}
		}
		
		try {
			echo "Building branch ${env.BRANCH_NAME}"
			Utilities.notifyBuildStatus(this, "Started")

			stage('Checkout') {
				timestamps { 
					checkout scm
				}				
			}

			if(Utilities.checkAndAbortBuild(this))
			{
				return true
			}

			stage('Build + Analyze') {		
				timestamps { 					
					// clean folder for a release
					if (Packaging.getShouldPublish(this)) {
						deleteDir()
						checkout scm
					}		
					
					Packaging.startAnalyzer(this)
					Packaging.runBuild(this, solution)
				}
			}
		
			def version = Utilities.getAssemblyVersion(this, webProject)
			def dockerImage

			stage('Package') {
				timestamps { 
					Packaging.createReleaseArtifact(this, version, webProject, zipArtifact, websiteDir)
					if (env.BRANCH_NAME == 'dev' || env.BRANCH_NAME == 'master') {
						def websitePath = Utilities.getWebPublishFolder(this, websiteDir)
						dockerImage = Packaging.createDockerImage(this, zipArtifact.replaceAll('\\.','/'), websitePath, ".", dockerTag)			
					}
				}
			}

			def tests = Utilities.getTestDlls(this)
			if(tests.size() > 0)
			{
				stage('Tests') {
					timestamps { 
						Packaging.runUnitTests(this, tests)
					}
				}
			}		

			stage('Submit Analysis') {
				timestamps { 
					Packaging.endAnalyzer(this)
				}
			}			

			// No need to occupy a node
			stage("Quality Gate"){
				Packaging.checkAnalyzerGate(this)
			}

			if(solution == 'VirtoCommerce.Platform.sln' || projectType == 'NETCORE2') // skip docker and publishing for NET4
			{
				if (env.BRANCH_NAME == 'dev' || env.BRANCH_NAME == 'master') {
					stage('Docker Sample') {
						timestamps { 
							// Start docker environment				
							Packaging.startDockerTestEnvironment(this, dockerTag)
							
							// install modules
							Packaging.installModules(this)	

							// now create sample data
							Packaging.createSampleData(this)					
						}
					}
				}			
			}

			if (env.BRANCH_NAME == 'dev' || env.BRANCH_NAME == 'master') {
				stage('Publish'){
					timestamps { 
						if(solution == 'VirtoCommerce.Platform.sln' || projectType == 'NETCORE2')
						{
							Packaging.pushDockerImage(this, dockerImage, dockerTag)
						}
						if (Packaging.getShouldPublish(this)) {
							def notes = Utilities.getReleaseNotes(this, webProject)
							Packaging.publishRelease(this, version, notes)
						}

						if(solution == 'VirtoCommerce.Platform.sln' || projectType == 'NETCORE2')
						{
							Utilities.runSharedPS(this, "resources\\azure\\${deployScript}")
						}
					}
				}
			}


/*
			stage('Cleanup') {
				timestamps { 
					Packaging.cleanBuild(this, solution)
				}
			}	
*/		
		}
		catch (any) {
			currentBuild.result = 'FAILURE'
			Utilities.notifyBuildStatus(this, currentBuild.result)
			throw any //rethrow exception to prevent the build from proceeding
		}
		finally {
			Packaging.stopDockerTestEnvironment(this, dockerTag)
			step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: emailextrecipients([[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']])])
	    	//step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: 'dev@virtoway.com', sendToIndividuals: true])
		}
	
	  	step([$class: 'GitHubCommitStatusSetter', statusResultSource: [$class: 'ConditionalStatusResultSource', results: []]])
		Utilities.notifyBuildStatus(this, currentBuild.result)
	}
}
