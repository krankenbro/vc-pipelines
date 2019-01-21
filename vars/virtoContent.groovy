def call(body) {

    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node
    {
        stage("Clean up"){
            deleteDir()
        }

        stage("Checkout"){
            timestamps{
                checkout scm
            }
        }

        stage("gulp"){
            bat "gulp"
        }
    }
}