def call(Map config = [:]) {

    /*
     * ============================================================
     * PREPARE ENVIRONMENT CHOICES
     * ============================================================
     */

    def environmentChoices = config.environments
        .keySet()
        .join('\n')


    pipeline {

        /*
         * ============================================================
         * JENKINS AGENT
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
         * ============================================================
         */

        parameters {

            choice(
                name: 'ENVIRONMENT',
                choices: "${environmentChoices}",
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
         * ENVIRONMENT VARIABLES
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
             * Terraform / Jenkins configuration
             */

            PROJECT  = "${config.projectName}"
            POC_NAME = "${config.pocName}"


            /*
             * Jenkins AWS credential
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
             * DISCOVER APPLICATION AND SHARED DATABASE
             *
             * Application EC2 tags:
             *
             * Project
             * Environment
             * component = app
             * Created_by
             * State = non-persistent
             *
             * Shared Database tags:
             *
             * Project
             * Environment
             * component = database
             * Created_by
             * Lifecycle = Persistent
             * ============================================================
             */

            stage('Discover Application and Database Infrastructure') {

                steps {

                    script {

                        /*
                         * Jenkins parameter values are:
                         *
                         * Dev
                         * QA
                         *
                         * Terraform AWS tags are:
                         *
                         * dev
                         * qa
                         */

                        def targetEnvLower = env.TARGET_ENV.toLowerCase()


                        /*
                         * =================================================
                         * AUTHENTICATE TO AWS
                         * =================================================
                         */

                        withCredentials([

                            [
                                $class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: "${env.AWS_CREDENTIALS}"
                            ]

                        ]) {


                            /*
                             * =================================================
                             * VERIFY AWS AUTHENTICATION
                             * =================================================
                             */

                            sh """
                                set -e

                                echo "=========================================="
                                echo "VERIFYING AWS AUTHENTICATION"
                                echo "=========================================="

                                aws sts get-caller-identity

                                echo ""
                                echo "AWS Region          : ${env.AWS_REGION}"
                                echo "Project             : ${env.PROJECT}"
                                echo "Jenkins Environment : ${env.TARGET_ENV}"
                                echo "AWS Tag Environment : ${targetEnvLower}"
                                echo "Created By          : ${env.POC_NAME}"

                                echo "=========================================="
                            """


                            /*
                             * =================================================
                             * DISCOVER APPLICATION EC2
                             * =================================================
                             */

                            def appPublicIp = sh(
                                script: """
                                    set -e

                                    aws ec2 describe-instances \
                                        --region "${env.AWS_REGION}" \
                                        --filters \
                                            "Name=tag:Project,Values=${env.PROJECT}" \
                                            "Name=tag:Environment,Values=${targetEnvLower}" \
                                            "Name=tag:component,Values=app" \
                                            "Name=tag:Created_by,Values=${env.POC_NAME}" \
                                            "Name=tag:State,Values=non-persistent" \
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
                                        --region "${env.AWS_REGION}" \
                                        --filters \
                                            "Name=tag:Project,Values=${env.PROJECT}" \
                                            "Name=tag:Environment,Values=${targetEnvLower}" \
                                            "Name=tag:component,Values=app" \
                                            "Name=tag:Created_by,Values=${env.POC_NAME}" \
                                            "Name=tag:State,Values=non-persistent" \
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
Application EC2 Public IP was not found.

Expected tags:

Project     = ${env.PROJECT}
Environment = ${targetEnvLower}
component   = app
Created_by  = ${env.POC_NAME}
State       = non-persistent
""")
                            }


                            if (!appPrivateIp || appPrivateIp == 'None') {

                                error("""
Application EC2 Private IP was not found.

Expected tags:

Project     = ${env.PROJECT}
Environment = ${targetEnvLower}
component   = app
Created_by  = ${env.POC_NAME}
State       = non-persistent
""")
                            }


                            /*
                             * =================================================
                             * DISCOVER SHARED DATABASE
                             * =================================================
                             */

                            def dbPublicIp = sh(
                                script: """
                                    set -e

                                    aws ec2 describe-instances \
                                        --region "${env.AWS_REGION}" \
                                        --filters \
                                            "Name=tag:Project,Values=${env.PROJECT}" \
                                            "Name=tag:Environment,Values=${targetEnvLower}" \
                                            "Name=tag:component,Values=database" \
                                            "Name=tag:Created_by,Values=${env.POC_NAME}" \
                                            "Name=tag:Lifecycle,Values=Persistent" \
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
                                        --region "${env.AWS_REGION}" \
                                        --filters \
                                            "Name=tag:Project,Values=${env.PROJECT}" \
                                            "Name=tag:Environment,Values=${targetEnvLower}" \
                                            "Name=tag:component,Values=database" \
                                            "Name=tag:Created_by,Values=${env.POC_NAME}" \
                                            "Name=tag:Lifecycle,Values=Persistent" \
                                            "Name=instance-state-name,Values=running" \
                                        --query 'Reservations[].Instances[].PrivateIpAddress' \
                                        --output text
                                """,
                                returnStdout: true
                            ).trim()


                            /*
                             * Validate Shared Database
                             */

                            if (!dbPublicIp || dbPublicIp == 'None') {

                                error("""
Shared Database Public IP was not found.

Expected tags:

Project     = ${env.PROJECT}
Environment = ${targetEnvLower}
component   = database
Created_by  = ${env.POC_NAME}
Lifecycle   = Persistent
""")
                            }


                            if (!dbPrivateIp || dbPrivateIp == 'None') {

                                error("""
Shared Database Private IP was not found.

Expected tags:

Project     = ${env.PROJECT}
Environment = ${targetEnvLower}
component   = database
Created_by  = ${env.POC_NAME}
Lifecycle   = Persistent
""")
                            }


                            /*
                             * =================================================
                             * STORE DISCOVERED INFRASTRUCTURE DETAILS
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
==================================================

APPLICATION EC2

Public IP  : ${env.APP_PUBLIC_IP}
Private IP : ${env.APP_PRIVATE_IP}

SHARED DATABASE

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
==================================================

Environment : ${env.TARGET_ENV}
Version     : ${env.APP_VERSION}

Application EC2

Public IP  : ${env.APP_PUBLIC_IP}
Private IP : ${env.APP_PRIVATE_IP}

Shared Database

Public IP  : ${env.DB_PUBLIC_IP}
Private IP : ${env.DB_PRIVATE_IP}

==================================================
"""
            }


            failure {

                echo """

==================================================
PIPELINE FAILED
==================================================

Environment : ${env.TARGET_ENV}

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