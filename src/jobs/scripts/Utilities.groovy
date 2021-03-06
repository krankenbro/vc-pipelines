package jobs.scripts

import groovy.io.FileType;

class Utilities {

    private static String DefaultSharedLibName = 'vc-pipeline'
    private static String DefaultAdminDockerPrefix = 'http://localhost'
    private static Integer DefaultPlatformPort = 8091
    private static Integer DefaultStorefrontPort = 8081
    private static Integer DefaultSqlPort = 4567

    /**
     * Get the folder name for a job.
     *
     * @param project Project name (e.g. dotnet/coreclr)
     * @return Folder name for the project. Typically project name with / turned to _
     */
    def static getFolderName(String project) {
        return project.replace('/', '_')
    }

    def static getRepoName(context)
    {
        context.echo "job name is \"${context.env.JOB_NAME}\""
        def tokens = "${context.env.JOB_NAME}".tokenize('/')
        def REPO_NAME = tokens[1]
        return REPO_NAME
    }

    def static getOrgName(context)
    {
        return "VirtoCommerce"
    }

    def static runSharedPS(context, scriptName, args = '')
    {
    	def wsFolder = context.pwd()
 	    context.bat "powershell.exe -File \"${wsFolder}\\..\\workspace@libs\\${DefaultSharedLibName}\\resources\\azure\\${scriptName}\" ${args} -ErrorAction Stop"
    }

    def static getAssemblyVersion(context, projectFile)
    {
        if(context.projectType == 'NETCORE2')
        {
            context.echo "Reading $projectFile file"

            def wsDir = context.pwd()
            def fullManifestPath = "$wsDir\\$projectFile"
            def manifest = new XmlSlurper().parse(fullManifestPath)

            def version = manifest.PropertyGroup.Version.toString()
            context.echo "Found version ${version}"
            return version
        }
        else
        {
            context.echo "Searching for version inside CommonAssemblyInfo.cs file"
            def matcher = context.readFile('CommonAssemblyInfo.cs') =~ /AssemblyFileVersion\(\"(\d+\.\d+\.\d+)/
            def version = matcher[0][1]
            context.echo "Found version ${version}"
            return version
        }
    }

    def static getPackageVersion(context)
    {
        context.echo "Searching for version inside package.json file"
        def inputFile = context.readFile('package.json')
        def json = Utilities.jsonParse(inputFile)

        def version = json.version
        context.echo "Found version ${version}"
        return version
    }

    def static getReleaseNotes(context, projectFile)
    {
        if(context.projectType == 'NETCORE2')
        {
            context.echo "Reading $projectFile file"

            def wsDir = context.pwd()
            def fullManifestPath = "$wsDir\\$projectFile"
            def manifest = new XmlSlurper().parse(fullManifestPath)

            def notes = manifest.PropertyGroup.PackageReleaseNotes.toString()
            context.echo "Found notes ${notes}"
            return notes
        }
        else
        {
            return ""
        }
    }    

    def static getTestDlls(context)
    {
        def testDlls = context.findFiles(glob: '**\\bin\\Debug\\*Test.dll')
        return testDlls
    }

    def static getComposeFolder(context)
    {
		def composeDir = "${context.env.WORKSPACE}@libs\\${DefaultSharedLibName}\\resources"
		if(context.projectType == 'NETCORE2') {
		    composeDir = "$composeDir\\docker.core\\windowsnano"
        } else {		   
		    composeDir = "$composeDir\\docker"
		}
        return composeDir
    }    

    def static getArtifactFolder(context)
    {
        def wsFolder = context.pwd()
        def packagesDir = "$wsFolder\\artifacts"
        return packagesDir
    }

    def static getCoverageFolder(context)
    {
        def wsFolder = Utilities.getTempFolder(context)
        def packagesDir = "$wsFolder\\.coverage"
        return packagesDir
    }    

    def static getWebPublishFolder(context, String websiteDir)
    {
        if(context.projectType == 'NETCORE2')
        {
            def tempFolder = Utilities.getTempFolder(context)
            def websitePath = "$tempFolder\\_PublishedWebsites\\${websiteDir}"
            return websitePath           
        }
        else
        {
            def tempFolder = Utilities.getTempFolder(context)
            def websitePath = "$tempFolder\\_PublishedWebsites\\${websiteDir}"
            return websitePath
        }
    }

    def static getPlatformHost(context)
    {
        return "${DefaultAdminDockerPrefix}:${getPlatformPort(context)}"
    }

    def static getTempFolder(context)
    {
        def tempFolder = context.pwd(tmp: true)
        return tempFolder
    }

    def static notifyBuildStatus(context, status)
    {
        context.office365ConnectorSend status:context.currentBuild.result, webhookUrl:context.env.O365_WEBHOOK
    }    

    def static getPlatformPort(context)
    {
        return DefaultPlatformPort.toInteger() + context.env.VC_BUILD_ORDER.toInteger();
    }

    def static getStorefrontPort(context)
    {
        return DefaultStorefrontPort.toInteger() + context.env.VC_BUILD_ORDER.toInteger();
    }    

    def static getSqlPort(context)
    {
        return DefaultSqlPort.toInteger() + context.env.VC_BUILD_ORDER.toInteger();
    }

    def static getNextBuildOrderExecutor(context)
    {
        return context.env.EXECUTOR_NUMBER;
    }

    def static getNextBuildOrder(context)
    {
        def instance = Jenkins.getInstance()
        def globalNodeProperties = instance.getGlobalNodeProperties()
        def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)

        def newEnvVarsNodeProperty = null
        def envVars = null

        if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {
            newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty();
            globalNodeProperties.add(newEnvVarsNodeProperty)
            envVars = newEnvVarsNodeProperty.getEnvVars()
        } else {
            envVars = envVarsNodePropertyList.get(0).getEnvVars()
        }

        def tempCurrentOrder = envVars.get("VC_BUILD_ORDER")    
        def currentOrder = 0

        if(tempCurrentOrder) // exists
        {
            currentOrder = tempCurrentOrder.toInteger() + 1
            
            if(currentOrder >= 10) // reset, we can't have more than 10 builders at the same time
            {
                currentOrder = 0
            }
        }

        envVars.put("VC_BUILD_ORDER", currentOrder.toString())
        instance.save()

        // save in current context
        context.env.VC_BUILD_ORDER = currentOrder

        return currentOrder
    }

    Object withDockerCredentials(Closure body) {
        withCredentials([[$class: 'ZipFileBinding', credentialsId: 'docker-hub-credentials', variable: 'DOCKER_CONFIG']]) {
            return body.call()
        }
    }    

    def static runUnitTest(context, traits, paths, resultsFileName)
    {
        def xUnitExecutable = "${context.env.XUnit}\\xunit.console.exe"
        def coverageExecutable = "${context.env.CodeCoverage}\\CodeCoverage.exe"
        def coverageFolder = Utilities.getCoverageFolder(context)

        // remove old folder
        context.dir(coverageFolder)
        {
            context.deleteDir()
        }        

        // recreate it now
        File folder = new File(coverageFolder); 
        if (!folder.mkdir()) { 
            throw new Exception("can't create coverage folder: " + coverageFolder); 
        } 

        context.bat "\"${coverageExecutable}\" collect /output:\"${coverageFolder}\\VisualStudio.Unit.coverage\" \"${xUnitExecutable}\" ${paths} -xml \"${resultsFileName}\" ${traits} -parallel none"
        context.bat "\"${coverageExecutable}\" analyze /output:\"${coverageFolder}\\VisualStudio.Unit.coveragexml\" \"${coverageFolder}\\VisualStudio.Unit.coverage\""
        context.step([$class: 'XUnitPublisher', testTimeMargin: '3000', thresholdMode: 1, thresholds: [[$class: 'FailedThreshold', failureNewThreshold: '', failureThreshold: '', unstableNewThreshold: '', unstableThreshold: ''], [$class: 'SkippedThreshold', failureNewThreshold: '', failureThreshold: '', unstableNewThreshold: '', unstableThreshold: '']], tools: [[$class: 'XUnitDotNetTestType', deleteOutputFiles: true, failIfNotNew: false, pattern: resultsFileName, skipNoTestFiles: true, stopProcessingIfError: false]]])
    }

    def static checkAndAbortBuild(context)
    {
		if(!Utilities.getShouldBuild(context))
		{
			Utilities.abortBuild(context)
		}
    }

    def static getShouldBuild(context)
    {
        String result = context.bat(returnStdout: true, script: "\"${context.tool 'Git'}\" log -1 --pretty=\"format:\" --name-only").trim()        
        def lines = result.split("\r?\n")

        //context.echo "size: ${lines.size()}, 2:${lines[1]}"
        if(lines.size() == 2 && lines[1].equalsIgnoreCase('readme.md'))
        {
            context.echo "Found only change to readme.md file, so build should be aborted."
            return false
        }

        return true
    }    

	def static getStagingNameFromBranchName(context){
	    def stagingName = ""
		if (context.env.BRANCH_NAME == 'dev')
		{
			stagingName = "dev"
		}
		if (context.env.BRANCH_NAME == 'master')
		{
			stagingName = "qa"
		}
		return stagingName
	}

    def static isNetCore(projectType){
        return projectType == 'NETCORE2'
    }

    def static sendMail(context, subject, body='', mailTo = null, mailFrom = null) {
        if(mailFrom == null){
            mailFrom = context.env.DefaultMailFrom
        }
        if(mailTo == null) {
            mailTo = context.env.DefaultMailTo
        }
        context.mail body: "Job url: ${context.env.JOB_URL}\n${body}", from: mailFrom, subject: "${context.env.JOB_NAME}:${context.env.BUILD_NUMBER} - ${subject}", to: mailTo
    }

    def static getFailedStageStr(logArray) {
        def startIndex = 30
        def i = 1
        for(logRow in logArray.reverse()){
            if(logRow =~ /\{\s\(.*\)/) {
                startIndex = i
                break
            }
            ++i
        }
        def result = logArray[logArray.size() - startIndex..-1].join("\r\n")
        return result
    }
    def static getFailedStageName(logText){
        def res = logText =~/(?ms).*\{\s\((.*)\).*/
        def name = ''
        if(res.matches())
            name = res.group(1)
        else
            name = 'Not found'
        return name
    }
    def static getMailBody(context, stageName, stageLog) {
        def result = "Failed Stage: ${stageName}\n${context.env.JOB_URL}\n\n\n${stageLog}"
        return result
    }

    def static getSwaggerDll(context){
        def swagger = context.findFiles(glob: '**\\*.Web.dll')
        return swagger
    }

    def static getWebApiDll(context){
        String swagPaths = ""
        def swagDlls = context.findFiles(glob: "**\\bin\\*.Web.dll")
        if(swagDlls.size() > 0)
        {
            for(swagDll in swagDlls){
                if(!swagDll.path.contains("VirtoCommerce.Platform.Core.Web.dll"))
                    swagPaths += "\"$swagDll.path\""
            }
        }
        return swagPaths
    }

    def static getPlatformContainer(context){
        def tag = context.env.BUILD_TAG.toLowerCase()
        def containerId = 'vc-platform-web'
        return "${tag}_${containerId}_1"
    }

    def static createNugets(context){
        String folderPath = "${context.env.WORKSPACE}\\NuGet"
        if(new File(folderPath).exists()){
            cleanNugetFolder(context)

            context.dir(folderPath){
                def buildFilePath = "${folderPath}\\build.bat"
                context.echo "build file path ${buildFilePath}"
                def buildFile = new File(buildFilePath)
                for (line in buildFile.readLines()) {
                    context.echo "next line: ${line}"
                    def res = findCsproj(context, line)
                    if(res){
                        context.echo res
                        def csprj = getCsprojPath(context, res).toString()
                        if(csprj){
                            context.echo csprj
                            def batCommand = "${context.env.NUGET}\\nuget pack \"${context.env.WORKSPACE}\\${csprj}\" -IncludeReferencedProjects -Symbols -Properties Configuration=Release"
                            context.echo batCommand
                            context.bat batCommand
                        }
                    }
                }
                def nugets = context.findFiles(glob: "**\\*.nupkg")
                for(nuget in nugets){
                    if(!nuget.name.contains("symbols")){
                        context.echo "publish nupkg: ${nuget.name}"
                        context.bat "${context.env.NUGET}\\nuget push ${nuget.name} -Source nuget.org -ApiKey ${context.env.NUGET_KEY}"
                    }
                }
            }
        }
    }
    def static getCsprojPath(context, name){
        context.dir(context.env.WORKSPACE){
            context.echo "File name is: ${name}"
            def projectFiles = context.findFiles(glob: "**\\${name}")
            context.echo "Found path: ${projectFiles[0].path}"
            return "${projectFiles[0].path}"
        }
    }
    @NonCPS
    def static cleanNugetFolder(context){
        String folderPath = "${context.env.WORKSPACE}\\NuGet"
        new File(folderPath).eachFile (FileType.FILES) { file ->
            context.echo "found file: ${file.name}"
            if (file.name.contains('nupkg')) {
                context.echo "remove ${file.name}"
                file.delete()
            }
        }
    }
    @NonCPS
    def static findCsproj(context, line){
        //def res = (line =~/(VirtoCommerce.+csproj)/)
        def res = (line =~/.*(VirtoCommerce.+csproj).*/)
        if(!res.matches()){
            context.echo "Not matches ${res.toString()}"
            return null
        }
        return res.group(1)
    }

    @NonCPS
    def static getPDBDirs(context){
        def pdbDirs = []
        def currentDir = new File(context.pwd())
        currentDir.eachDirRecurse(){ dir->
            if(dir.getPath() =~ /.*\\bin/)
                pdbDirs << dir.path
        }
        return pdbDirs
    }
    def static getPDBDirsStr(context){
        return getPDBDirs(context).join(';')
    }

    @NonCPS
    def static jsonParse(def json) {
        new groovy.json.JsonSlurperClassic().parseText(json)
    }    

    @NonCPS
    def static abortBuildIfTriggeredByJenkins(context) {
        def validChangeDetected = false
        def changeLogSets = context.currentBuild.changeSets
        for (int i = 0; i < changeLogSets.size(); i++) {
            def entries = changeLogSets[i].items
            for (int j = 0; j < entries.length; j++) {
                def entry = entries[j]
                if(!entry.msg.matches("\\[ci-skip\\].*")){
                    validChangeDetected = true
                    println "Found commit by ${entry.author}"
                }
            }
        }
        // We are building if there are some walid changes or if there are no changes(so the build was triggered intentionally or it is the first run.)
        if(!validChangeDetected && changeLogSets.size() != 0) {
            context.currentBuild.setResult(context.currentBuild.rawBuild.getPreviousBuild()?.result?.toString())
            context.error("Stopping current build")
        }
    }    

    @NonCPS
    def static abortBuild(context) {
        def validChangeDetected = false

        def changeLogSets = context.currentBuild.changeSets

        // We are building if there are some walid changes or if there are no changes(so the build was triggered intentionally or it is the first run.)
        if(changeLogSets.size() != 0) {
            context.echo "Aborting build and setting result to ${context.currentBuild.rawBuild.getPreviousBuild()?.result?.toString()}"
            context.currentBuild.setResult(context.currentBuild.rawBuild.getPreviousBuild()?.result?.toString())
            //context.echo "current build aborted"
            //context.error("Stopping current build")
            return true
        }
    }    
}
