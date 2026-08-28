def call(Map config) {


pipeline {

    /*
     * ============================================================
     * JENKINS AGENT
     *
     * AWS CLI is installed on this agent.
     * ============================================================
     */

    agent {
        node {
            label '007'
        }
    }


    /*
     * ============================================================
     * PIPELINE PARAMETERS
     *
     * ENVIRONMENT is selected when triggering the pipeline.
     * VERSION must follow semantic versioning.
     * ============================================================
     */

    parameters {

        choice(
            name: 'ENVIRONMENT',
            choices: config.environments.keySet().toList(),
            description: 'Select target environment'
        )

        string(
            name: 'VERSION',
            defaultValue: '1.0.0',
            description: 'Application version in semantic version format, for example: 1.0.0'
        )
    }


    /*
     * ============================================================
     * GLOBAL ENVIRONMENT VARIABLES
     * ============================================================
     */

    environment {

        /*
         * Pipeline parameters
         */

        APP_VERSION = "${params.VERSION}"
        TARGET_ENV  = "${params.ENVIRONMENT}"


        /*
         * AWS configuration
         */

        ACCOUNT_ID = "${config.accountId}"
        AWS_REGION = "${config.awsRegion}"


        /*
         * Terraform / Jenkins configuration values
         *
         * Used for AWS EC2 discovery.
         */

        PROJECT  = "${config.projectName}"
        POC_NAME = "${config.pocName}"


        /*
         * Jenkins credentials
         */

        AWS_CREDENTIALS = "${config.awsCredentials ?: 'aws-erp'}"
    }


    /*
     * ============================================================
     * PIPELINE OPTIONS
     * ============================================================
     */

    options {

        disableConcurrentBuilds()

        timestamps()
    }


    /*
     * ============================================================
     * PIPELINE STAGES
     * ============================================================
     */

    stages {


        /*
         * ============================================================
         * PREPARE WORKSPACE
         *
         * Remove files left from previous builds before continuing.
         * ============================================================
         */

        stage('Prepare Workspace') {

            steps {

                cleanWs()

                echo "Workspace cleaned successfully"
            }
        }


        /*
         * ============================================================
         * STAGE 1
         *
         * DISCOVER APPLICATION AND DATABASE INFRASTRUCTURE
         *
         * Authenticate to AWS.
         *
         * Discover:
         *
         * Application EC2
         *   - Public IP
         *   - Private IP
         *
         * Shared Database EC2
         *   - Public IP
         *   - Private IP
         *
         * Store discovered values as Jenkins environment variables
         * for use in later pipeline stages.
         * ============================================================
         */

        stage('Discover Application and Database Infrastructure') {

            steps {

                script {

                    /*
                     * ====================================================
                     * AUTHENTICATE TO AWS
                     * ====================================================
                     */

                    withCredentials([

                        [
                            $class: 'AmazonWebServicesCredentialsBinding',
                            credentialsId: "${AWS_CREDENTIALS}"
                        ]

                    ]) {


                        /*
                         * =================================================
                         * VERIFY AWS ACCESS
                         * =================================================
                         */

                        sh """
                            set -e

                            echo "=========================================="
                            echo "VERIFYING AWS AUTHENTICATION"
                            echo "=========================================="

                            aws sts get-caller-identity

                            echo ""
                            echo "AWS Region  : ${AWS_REGION}"
                            echo "Project     : ${PROJECT}"
                            echo "Environment : ${TARGET_ENV}"
                            echo "Created By  : ${POC_NAME}"

                            echo "=========================================="
                        """


                        /*
                         * =================================================
                         * DISCOVER APPLICATION EC2
                         *
                         * Terraform Tags:
                         *
                         * Project     = var.project_name
                         * Environment = var.environment
                         * State       = non-persistent
                         * Created_by  = var.poc_name
                         * component   = app
                         * =================================================
                         */

                        def appPublicIp = sh(

                            script: """

                                set -e

                                aws ec2 describe-instances \
                                    --region "${AWS_REGION}" \
                                    --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${TARGET_ENV}" \
                                        "Name=tag:State,Values=non-persistent" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:component,Values=app" \
                                        "Name=instance-state-name,Values=running" \
                                    --query 'Reservations[].Instances[].PublicIpAddress' \
                                    --output text

                            """,

                            returnStdout: true

                        ).trim()


                        def appPrivateIp = sh(

                            script: """

                                set -e

                                aws ec2 describe-instances \
                                    --region "${AWS_REGION}" \
                                    --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${TARGET_ENV}" \
                                        "Name=tag:State,Values=non-persistent" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:component,Values=app" \
                                        "Name=instance-state-name,Values=running" \
                                    --query 'Reservations[].Instances[].PrivateIpAddress' \
                                    --output text

                            """,

                            returnStdout: true

                        ).trim()


                        /*
                         * Validate Application EC2
                         */

                        if (!appPublicIp || appPublicIp == 'None') {

                            error("""
```

Application EC2 Public IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${TARGET_ENV}
State       = non-persistent
Created_by  = ${POC_NAME}
component   = app
""")
}


                        if (!appPrivateIp || appPrivateIp == 'None') {

                            error("""


Application EC2 Private IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${TARGET_ENV}
State       = non-persistent
Created_by  = ${POC_NAME}
component   = app
""")
}


                        /*
                         * =================================================
                         * DISCOVER SHARED DATABASE EC2
                         *
                         * Terraform Tags:
                         *
                         * Component   = Database
                         * State       = Persistent
                         * Project     = var.project_name
                         * Environment = var.environment
                         * Created_by  = var.poc_name
                         * =================================================
                         */

                        def dbPublicIp = sh(

                            script: """

                                set -e

                                aws ec2 describe-instances \
                                    --region "${AWS_REGION}" \
                                    --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${TARGET_ENV}" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:Component,Values=Database" \
                                        "Name=tag:State,Values=Persistent" \
                                        "Name=instance-state-name,Values=running" \
                                    --query 'Reservations[].Instances[].PublicIpAddress' \
                                    --output text

                            """,

                            returnStdout: true

                        ).trim()


                        def dbPrivateIp = sh(

                            script: """

                                set -e

                                aws ec2 describe-instances \
                                    --region "${AWS_REGION}" \
                                    --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${TARGET_ENV}" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:Component,Values=Database" \
                                        "Name=tag:State,Values=Persistent" \
                                        "Name=instance-state-name,Values=running" \
                                    --query 'Reservations[].Instances[].PrivateIpAddress' \
                                    --output text

                            """,

                            returnStdout: true

                        ).trim()


                        /*
                         * Validate Shared Database EC2
                         */

                        if (!dbPublicIp || dbPublicIp == 'None') {

                            error("""


Shared Database Public IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${TARGET_ENV}
Created_by  = ${POC_NAME}
Component   = Database
State       = Persistent
""")
}


                        if (!dbPrivateIp || dbPrivateIp == 'None') {

                            error("""


Shared Database Private IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${TARGET_ENV}
Created_by  = ${POC_NAME}
Component   = Database
State       = Persistent
""")
}


                        /*
                         * =================================================
                         * STORE DISCOVERED INFRASTRUCTURE DETAILS
                         *
                         * Available to all later stages.
                         * =================================================
                         */

                        env.APP_PUBLIC_IP  = appPublicIp
                        env.APP_PRIVATE_IP = appPrivateIp

                        env.DB_PUBLIC_IP   = dbPublicIp
                        env.DB_PRIVATE_IP  = dbPrivateIp


                        /*
                         * =================================================
                         * DISPLAY DISCOVERED INFRASTRUCTURE
                         * =================================================
                         */

                        echo """


==================================================
INFRASTRUCTURE DISCOVERED SUCCESSFULLY
======================================

APPLICATION EC2

Public IP  : ${env.APP_PUBLIC_IP}
Private IP : ${env.APP_PRIVATE_IP}

SHARED DATABASE EC2

Public IP  : ${env.DB_PUBLIC_IP}
Private IP : ${env.DB_PRIVATE_IP}

==================================================
"""
}
}
}
}
}


    /*
     * ============================================================
     * POST ACTIONS
     * ============================================================
     */

    post {

        success {

            echo """


==================================================
PIPELINE COMPLETED SUCCESSFULLY
===============================

Environment : ${TARGET_ENV}
Version     : ${APP_VERSION}

Application EC2

Public IP  : ${APP_PUBLIC_IP}
Private IP : ${APP_PRIVATE_IP}

Shared Database EC2

Public IP  : ${DB_PUBLIC_IP}
Private IP : ${DB_PRIVATE_IP}

==================================================
"""
}


        failure {

            echo """


==================================================
PIPELINE FAILED
===============

Environment : ${TARGET_ENV}

Check the Jenkins stage logs.

==================================================
"""
}


        always {

            cleanWs()
        }
    }
}


}
