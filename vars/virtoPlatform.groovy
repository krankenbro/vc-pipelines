#!groovy
import groovy.io.FileType
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
	node () {
		def isCaused = params.isCaused;
		if(isCaused == null){
			isCaused = false
		}
		if(solution == null) {
			solution = "VirtoCommerce.Platform.sln"
		}
		if(projectType == null) {
			projectType = "NET4"
		}
		def dockerTag = "${env.BRANCH_NAME}-branch"

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
						bat "\"${tool 'DefaultMSBuild'}\\msbuild.exe\" \"${solution}\" /p:Configuration=Debug /p:Platform=\"Any CPU\" /t:restore /t:rebuild /m /p:DebugType=Full"
					}
					else  { //platform
						bat "${env.NUGET}\\nuget restore ${solution}"
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
						String coverageFolder = Utilities.getCoverageFolder(this)
						// remove old folder
						dir(coverageFolder)
								{
									deleteDir()
								}

						// recreate it now
						File folder = new File(coverageFolder);
						if (!folder.mkdirs()) {
							throw new Exception("can't create coverage folder: " + coverageFolder);
						}
						for(int i = 0; i < tests.size(); i++)
						{
							def test = tests[i]
							paths += "\"$test.path\" "
						}


						if(projectType == "NETCORE2") {
							//bat "dotnet vstest ${paths} --TestCaseFilter:\"Category=Unit\""
							def pdbDirs = Utilities.getPDBDirsStr(this)
							bat "\"${env.OPENCOVER}\\opencover.console.exe\" -oldStyle -searchdirs:\"${pdbDirs}\" -register:user -filter:\"+[Virto*]* -[xunit*]*\" -output:\"${coverageFolder}\\VisualStudio.Unit.coveragexml\" -target:\"${env.VSTEST_DIR}\\vstest.console.exe\" -targetargs:\"${paths} /TestCaseFilter:(Category=Unit|Category=ci)\""
						}
						else {
							def pdbDirs = Utilities.getPDBDirsStr(this)
							bat "\"${env.OPENCOVER}\\opencover.console.exe\" -searchdirs:\"${pdbDirs}\" -register:user -filter:\"+[Virto*]* -[xunit*]*\" -output:\"${coverageFolder}\\VisualStudio.Unit.coveragexml\" -target:\"${env.VSTEST_DIR}\\vstest.console.exe\" -targetargs:\"${paths} /TestCaseFilter:(Category=Unit|Category=ci)\""
						}
					}
				}
			}
            def zipArtifact = 'VirtoCommerce.Platform'
            def websiteDir = 'VirtoCommerce.Platform.Web'
            def webProject = 'VirtoCommerce.Platform.Web\\VirtoCommerce.Platform.Web.csproj'

            if(env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'dev')
            {
                if (env.BRANCH_NAME == 'master') {
                    dockerTag = "latest"
                }

                if(Utilities.isNetCore(projectType)){
                    websiteDir = 'VirtoCommerce.Storefront'
                    webProject = 'VirtoCommerce.Storefront\\VirtoCommerce.Storefront.csproj'
                    zipArtifact = 'VirtoCommerce.StoreFront'
                }
            }

            def version = Utilities.getAssemblyVersion(this, webProject)
            stage('Package') {
                timestamps {
                    Packaging.createReleaseArtifact(this, version, webProject, zipArtifact, websiteDir)
                    def websitePath = Utilities.getWebPublishFolder(this, websiteDir)
                    dockerImage = Packaging.createDockerImage(this, zipArtifact.replaceAll('\\.','/'), websitePath, ".", dockerTag)
					Packaging.createNugetPackages(this)
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
					//Packaging.checkAnalyzerGate(this)
				}
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

							//check installed modules
							Packaging.checkInstalledModules(this)

                            // now create sample data
                            Packaging.createSampleData(this)
                        }
                    }
                }
            }

            if(!Utilities.isNetCore(projectType) && (env.BRANCH_NAME == 'dev' || env.BRANCH_NAME == 'master')){
                stage('Swagger Validation') {
                    timestamps {
                        def tempFolder = Utilities.getTempFolder(this)
                        def swaggerFile = "${tempFolder}\\swagger.json"
                        Packaging.createSwaggerSchema(this, swaggerFile)
                        bat "swagger-cli validate ${swaggerFile}"
                    }
                }
            }

			echo "Is it Caused? ${isCaused}"




		}
		catch(Throwable e) {
			currentBuild.result = 'FAILURE'
			def log = currentBuild.rawBuild.getLog(300)
			def failedStageLog = Utilities.getFailedStageStr(log)
			def failedStageName = Utilities.getFailedStageName(failedStageLog)
			Utilities.sendMail this, "${currentBuild.currentResult}", "${e.getMessage()}\n${e.getCause()}\n${failedStageName} \n\n${failedStageLog}"
			throw e
		}
		finally {
			Packaging.stopDockerTestEnvironment(this, dockerTag)
			if(currentBuild.result != 'FAILURE') {
				Utilities.sendMail(this, "${currentBuild.currentResult}")
			}
		}
	}
}
