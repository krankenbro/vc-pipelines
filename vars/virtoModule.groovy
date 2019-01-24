#!groovy
import groovy.json.*
import groovy.util.*
import jobs.scripts.*
import groovy.io.FileType

def call(body) {

	// evaluate the body block, and collect configuration into the object
	def config = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	projectType = config.projectType
	body()

	node {

		def dockerTag = "${env.BRANCH_NAME}-branch"

		if (projectType == null) {
			projectType = "NET4"
		}

		try {
			stage("Checkout") {
				timestamps {
					checkout scm
				}
			}

			stage("Build") {
				timestamps {
					def solutions = findFiles(glob: '*.sln')


					if (solutions.size() > 0) {
						Packaging.startAnalyzer(this)
						for (int i = 0; i < solutions.size(); i++) {
							def solution = solutions[i]
							bat "${env.NUGET}\\nuget restore ${solution}"
							bat "\"${tool 'DefaultMSBuild'}\\msbuild.exe\" \"${solution}\" /p:Configuration=Debug /p:Platform=\"Any CPU\" /t:rebuild /m"
						}
					}
				}
			}

			stage('Package Module')
			{
				timestamps {
					processManifests(false) // prepare artifacts for testing

					Packaging.createNugetPackages(this)


//                    String nugetFolder = "${env.WORKSPACE}\\NuGet"
//
//					def solutions = findFiles(glob: "**\\*.sln")
//					for(solution in solutions){
//						bat "\"${tool 'DefaultMSBuild'}\\msbuild.exe\" \"${solution.path}\" /nologo /verbosity:n /t:Build /p:Configuration=Release;Platform=\"Any CPU\""
//					}
//
//					Utilities.cleanNugetFolder(this)
//					def nuspecs = findFiles glob: "**\\*.nuspec"
//					def csprojs = []
//					for (nuspec in nuspecs){
//						def nuspecParent = new File(nuspec.path).getParent()
//						def found = findFiles(glob: "**\\${nuspecParent}\\*.csproj")
//						if(found){
//							csprojs.addAll(found)
//						}
//
//					}
//					dir(nugetFolder){
//						for(csproj in csprojs){
//							bat "${env.NUGET}\\nuget pack \"${env.WORKSPACE}\\${csproj.path}\" -IncludeReferencedProjects -Symbols -Properties Configuration=Release"
//						}
//						def nugets = findFiles(glob: "**\\*.nupkg")
//						for(nuget in nugets){
//							if(!nuget.name.contains("symbols")){
//								echo "publish nupkg: ${nuget.name}"
//								bat "${env.NUGET}\\nuget push ${nuget.name} -Source nuget.org -ApiKey ${env.NUGET_KEY}"
//							}
//						}
//					}
//					def csprojs = findFiles glob: "**\\*.csproj"
//					for(csproj in csprojs){
//						csprojParent =
//					}
//
//						dir(folderPath){
//							def buildFilePath = "${folderPath}\\build.bat"
//							echo "build file path ${buildFilePath}"
//							def buildFile = new File(buildFilePath)
//							for (line in buildFile.readLines()) {
//								def res = Utilities.findCsproj(this, line)
//								echo "next line: ${line}"
//								if(res){
//									def command = "${env.NUGET}\\nuget pack \"${env.WORKSPACE}\\${res[0]}\" -IncludeReferencedProjects -Symbols -Properties Configuration=Release"
//									echo command
//									bat command
//								}
//							}
//						}
//					}

//					withEnv(["MSBUILD_PATH=${tool 'DefaultMSBuild'}\\msbuild.exe", "PATH+=${env.NUGET}\\nuget.exe"]){
//						bat "build ${env.WORKSPACE}"
//					}
				}
			}

			def tests = Utilities.getTestDlls(this)
			if (projectType == "NETCORE2" && tests.size() < 1) {
				tests = findFiles(glob: '**\\bin\\Debug\\*\\*Tests.dll')
			}
			if (tests.size() > 0) {
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
						for (int i = 0; i < tests.size(); i++) {
							def test = tests[i]
							paths += "\"$test.path\" "
						}
						if (projectType == "NETCORE2") {
							bat "dotnet vstest ${paths} --TestCaseFilter:\"Category=Unit\""
						} else {
							def pdbDirs = Utilities.getPDBDirsStr(this)
							bat "\"${env.OPENCOVER}\\opencover.console.exe\" -searchdirs:\"${pdbDirs}\" -output:\"${coverageFolder}\\VisualStudio.Unit.coveragexml\" -register:user -target:\"${env.VSTEST_DIR}\\vstest.console.exe\" -targetargs:\"${paths} /TestCaseFilter:(Category=Unit|Category=CI)\""
						}
					}
				}
			}
			stage('Stop Analyze') {
				timestamps {
					Packaging.endAnalyzer(this)
				}
			}

			// No need to occupy a node
			stage("Quality Gate") {
				timestamps {
					Packaging.checkAnalyzerGate(this)
				}
			}

			if (env.BRANCH_NAME == 'dev' || env.BRANCH_NAME == 'master') {
				def buildOrder = Utilities.getNextBuildOrder(this)
				projectType = config.projectType
				if (env.BRANCH_NAME == 'master') {
					dockerTag = "latest"
				}
//                if (env.BRANCH_NAME == 'master') {
//                    stage('Build platform and storefront') {
//                        timestamps{
//                            build(job: "../vc-platform/${env.BRANCH_NAME}", parameters: [booleanParam(name: 'isCaused', value: true)])
//                            build(job: "../vc-storefront-core/${env.BRANCH_NAME}", parameters: [booleanParam(name: 'isCaused', value: true)])
//                        }
//                    }
//                }
				stage('Prepare Test Environment') {
					timestamps {
						def moduleId = Modules.getModuleId(this)
						def platformContainer = Utilities.getPlatformContainer(this)
						// Start docker environment
						Packaging.startDockerTestEnvironment(this, dockerTag)

						// install modules
						Packaging.installModules(this)

						// install module
						Modules.installModuleArtifacts(this, moduleId, platformContainer)

						//check installed modules
						Packaging.checkInstalledModules(this)

						// now create sample data
						Packaging.createSampleData(this)
					}
				}

				stage('Theme build and deploy'){
					def themePath = "${env.WORKSPACE}@tmp\\theme.zip"
					build(job: "../vc-theme-default/${env.BRANCH_NAME}", parameters: [string(name: 'themeResultZip', value: themePath)])
					Packaging.installTheme(this, themePath)
				}

				stage('Swagger Validation') {
					timestamps {
						def tempFolder = Utilities.getTempFolder(this)
						def swaggerFile = "${tempFolder}\\swagger.json"
						Packaging.createSwaggerSchema(this, swaggerFile)
						bat "swagger-cli validate ${swaggerFile}"
					}
				}

				stage('E2E') {
					timestamps {
						def tmp = Utilities.getTempFolder(this)
						def e2eDir = "${tmp}\\e2e"
						dir(e2eDir) {
							deleteDir()
							git credentialsId: 'github', url: 'https://github.com/VirtoCommerce/vc-platform-qg.git'
							def sfPort = Utilities.getStorefrontPort(this)
							//CODECEPT_OUTPUT value must be escaped
							def allureResultsPath = "${env.WORKSPACE}\\allure-results"
							def allureReportPath = "${env.WORKSPACE}\\allure-report"
							dir(allureReportPath){
								deleteDir()
							}
							def allureResultsEsc = allureResultsPath.replace("\\", "\\\\")
							def jsonConf = "{\\\"output\\\":\\\"${allureResultsEsc}\\\", \\\"helpers\\\":{\\\"WebDriverIO\\\":{\\\"url\\\":\\\"http://localhost:${sfPort}\\\"}}}"
							bat "codeceptjs run --steps -o \"${jsonConf}\""
						}
					}
				}
			}

			stage('Cleanup') {
				timestamps {
					Packaging.cleanSolutions(this)
				}
			}
		}
		catch (Throwable e) {
			currentBuild.result = 'FAILURE'
			def log = currentBuild.rawBuild.getLog(300)
			def failedStageLog = Utilities.getFailedStageStr(log)
			def failedStageName = Utilities.getFailedStageName(failedStageLog)
			Utilities.sendMail this, "${currentBuild.currentResult}", "${e.getMessage()}\n${e.getCause()}\n${failedStageName} \n\n${failedStageLog}"
			throw e
		}
		finally {
			allure includeProperties: false, jdk: '', results: [[path: "./../workspace@tmp/output"]]
			Packaging.stopDockerTestEnvironment(this, dockerTag)
			if (currentBuild.result != 'FAILURE') {
				Utilities.sendMail(this, "${currentBuild.currentResult}")
			}
		}
	}
}

def processManifests(publish)
{
	// find all manifests
	def manifests = findFiles(glob: '**\\module.manifest')

	if (manifests.size() > 0) {
		for (int i = 0; i < manifests.size(); i++)
		{
			def manifest = manifests[i]
			processManifest(publish, manifest.path)
		}
	}
	else {
		echo "no module.manifest files found"
	}
}

def processManifest(def publish, def manifestPath)
{
	def wsDir = pwd()
	def fullManifestPath = "$wsDir\\$manifestPath"

	echo "parsing $fullManifestPath"
	def manifest = new XmlSlurper().parse(fullManifestPath)

	echo "Upading module ${manifest.id}"
	def id = manifest.id.toString()

	def version = manifest.version.toString()
	def platformVersion = manifest.platformVersion.toString()
	def title = manifest.title.toString()
	def description = manifest.description.toString()
	def projectUrl = manifest.projectUrl.toString()
	def packageUrl = manifest.packageUrl.toString()
	def iconUrl = manifest.iconUrl.toString()
	def releaseNotes = manifest.releaseNotes.toString()

	// get dependencies
	echo "parsing dependencies"
	def dependencies = []
	for (int i = 0; i < manifest.dependencies.dependency.size(); i++)
	{
		def dependency = manifest.dependencies.dependency[i]
		def dependencyObj = [id: dependency['@id'].text(), version: dependency['@version'].text()]
		dependency = null
		dependencies.add(dependencyObj)
	}

	def owners = []
	echo "parsing owners"
	for (int i = 0; i < manifest.owners.owner.size(); i++)
	{
		def owner = manifest.owners.owner[i]
		owners.add(owner.text())
	}

	def authors = []
	echo "parsing authors"
	for (int i = 0; i < manifest.authors.author.size(); i++)
	{
		def author = manifest.authors.author[i]
		authors.add(author.text())
	}

	echo "manifest = null"
	manifest = null

	def manifestDirectory = manifestPath.substring(0, manifestPath.length() - 16)
	echo "prepare release $manifestDirectory"
	Modules.createModuleArtifact(this, manifestDirectory)

}

def publishTweet(def status)
{
	//bat "powershell.exe -File \"${env.JENKINS_HOME}\\workflow-libs\\vars\\twitter.ps1\" -status \"${status}\""
}

def updateModule(def id, def version, def platformVersion, def title, def authors, def owners, def description, def dependencies, def projectUrl, def packageUrl, def iconUrl)
{
	// MODULES
	dir('modules') {
		checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'sasha-jenkins', url: 'git@github.com:VirtoCommerce/vc-modules.git']]])

		def inputFile = readFile file: 'modules.json', encoding: 'utf-8'
		def json = Utilities.jsonParse(inputFile)
		parser = null
		def builder = new JsonBuilder(json)

		def foundRecord = false

		for (rec in json) {
			if (rec.id == id) {
               	echo "Modifying existing record in modules.json"
				rec.description = description
				rec.title = title
				rec.version = version
				rec.platformVersion = platformVersion
				rec.description = description
				rec.dependencies = dependencies
				if (projectUrl != null && projectUrl.length() > 0) {
					rec.projectUrl = projectUrl
				}
				if (packageUrl != null && packageUrl.length() > 0) {
					rec.packageUrl = packageUrl
				}
				if (iconUrl != null && iconUrl.length() > 0) {
					rec.iconUrl = iconUrl
				}

				rec.dependencies = dependencies
				rec.authors = authors
				rec.owners = owners

                foundRecord = true
				break
			}
		}

		if (!foundRecord) {
			// create new
			echo "Creating new record in modules.json"
			json.add([
				id: id,
				title: title,
				version: version,
				platformVersion: platformVersion,
				authors: authors,
				owners: owners,
				description: description,
				dependencies: dependencies,
				projectUrl: projectUrl,
				packageUrl: packageUrl,
				iconUrl: iconUrl
			])
		}

		def moduleJson = builder.toString()
		builder = null
		def prettyModuleJson = JsonOutput.prettyPrint(moduleJson.toString())
		//println(moduleJson)
		writeFile file: 'modules.json', text: prettyModuleJson
	}

	Packaging.updateModulesDefinitions(this, 'modules', id, version)
}