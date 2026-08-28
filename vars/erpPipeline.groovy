def call(Map config = [:]) {

    /*
     * ============================================================
     * PIPELINE PARAMETERS
     * ============================================================
     */

    def environmentChoices = config.environments?.keySet()?.join('\n') ?: 'Dev\nQA'

    properties([
        parameters([
            choice(
                name: 'ENVIRONMENT',
                choices: environmentChoices,
                description: 'Select the target deployment environment'
            ),

            string(
                name: 'VERSION',
                defaultValue: '',
                description: 'Application version using semantic versioning. Example: 1.0.0'
            )
        ])
    ])


    /*
     * ============================================================
     * PIPELINE
     * ============================================================
     */

    pipeline {

        agent {
            label config.agentLabel ?: 'jenkins-agent-007'
        }


        options {

            timestamps()

            skipDefaultCheckout(true)

        }


        environment {

            /*
             * --------------------------------------------------------
             * PIPELINE PARAMETERS
             * --------------------------------------------------------
             */

            APP_VERSION = "${params.VERSION}"

            TARGET_ENV = "${params.ENVIRONMENT}"


            /*
             * --------------------------------------------------------
             * AWS CONFIGURATION
             * --------------------------------------------------------
             */

            ACCOUNT_ID = "${config.accountId}"

            AWS_REGION = "${config.awsRegion}"


            /*
             * --------------------------------------------------------
             * TERRAFORM / PROJECT CONFIGURATION
             * --------------------------------------------------------
             */

            PROJECT = "${config.projectName}"

            POC_NAME = "${config.pocName}"

        }


        stages {


            /*
             * ========================================================
             * PREPARE WORKSPACE
             * ========================================================
             */

            stage('Prepare Workspace') {

                steps {

                    cleanWs()

                    echo "Workspace cleaned successfully"

                    checkout scm

                }

            }


            /*
             * ========================================================
             * DISCOVER APPLICATION AND DATABASE INFRASTRUCTURE
             * ========================================================
             */

            stage('Discover Application and Database Infrastructure') {

                steps {

                    script {

                        /*
                         * ------------------------------------------------
                         * ENVIRONMENT MAPPING
                         *
                         * Jenkins parameter:
                         * Dev
                         * QA
                         *
                         * AWS EC2 tag values:
                         * dev
                         * qa
                         * ------------------------------------------------
                         */

                        def awsEnvironment = TARGET_ENV.toLowerCase()


                        /*
                         * ------------------------------------------------
                         * VALIDATE AWS CREDENTIALS
                         * ------------------------------------------------
                         */

                        withCredentials([
                            [
                                $class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: config.awsCredentials
                            ]
                        ]) {


                            sh """
                                set -e

                                echo "=========================================="
                                echo "VERIFYING AWS AUTHENTICATION"
                                echo "=========================================="

                                aws sts get-caller-identity

                                echo ""
                                echo "AWS Region          : ${AWS_REGION}"
                                echo "Project             : ${PROJECT}"
                                echo "Jenkins Environment : ${TARGET_ENV}"
                                echo "AWS Tag Environment : ${awsEnvironment}"
                                echo "Created By          : ${POC_NAME}"

                                echo "=========================================="
                            """


                            /*
                             * ====================================================
                             * APPLICATION EC2
                             *
                             * Application server has:
                             *
                             * Public IP
                             * Private IP
                             * ====================================================
                             */


                            env.APP_PUBLIC_IP = sh(
                                script: """
                                    set -e

                                    aws ec2 describe-instances \
                                      --region ${AWS_REGION} \
                                      --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${awsEnvironment}" \
                                        "Name=tag:component,Values=app" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:State,Values=non-persistent" \
                                        "Name=instance-state-name,Values=running" \
                                      --query "Reservations[].Instances[].PublicIpAddress" \
                                      --output text
                                """,
                                returnStdout: true
                            ).trim()


                            env.APP_PRIVATE_IP = sh(
                                script: """
                                    set -e

                                    aws ec2 describe-instances \
                                      --region ${AWS_REGION} \
                                      --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${awsEnvironment}" \
                                        "Name=tag:component,Values=app" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:State,Values=non-persistent" \
                                        "Name=instance-state-name,Values=running" \
                                      --query "Reservations[].Instances[].PrivateIpAddress" \
                                      --output text
                                """,
                                returnStdout: true
                            ).trim()


                            /*
                             * ====================================================
                             * SHARED DATABASE EC2
                             *
                             * Database is in PRIVATE SUBNET.
                             *
                             * We ONLY retrieve Private IP.
                             *
                             * No Public IP query.
                             * ====================================================
                             */


                            env.DB_PRIVATE_IP = sh(
                                script: """
                                    set -e

                                    aws ec2 describe-instances \
                                      --region ${AWS_REGION} \
                                      --filters \
                                        "Name=tag:Project,Values=${PROJECT}" \
                                        "Name=tag:Environment,Values=${awsEnvironment}" \
                                        "Name=tag:component,Values=database" \
                                        "Name=tag:Created_by,Values=${POC_NAME}" \
                                        "Name=tag:Lifecycle,Values=Persistent" \
                                        "Name=instance-state-name,Values=running" \
                                      --query "Reservations[].Instances[].PrivateIpAddress" \
                                      --output text
                                """,
                                returnStdout: true
                            ).trim()


                            /*
                             * ====================================================
                             * VALIDATE DISCOVERED INFRASTRUCTURE
                             * ====================================================
                             */


                            if (!env.APP_PUBLIC_IP) {

                                error """
Application EC2 Public IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${awsEnvironment}
component   = app
Created_by  = ${POC_NAME}
State       = non-persistent
"""

                            }


                            if (!env.APP_PRIVATE_IP) {

                                error """
Application EC2 Private IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${awsEnvironment}
component   = app
Created_by  = ${POC_NAME}
State       = non-persistent
"""

                            }


                            if (!env.DB_PRIVATE_IP) {

                                error """
Shared Database Private IP was not found.

Expected tags:

Project     = ${PROJECT}
Environment = ${awsEnvironment}
component   = database
Created_by  = ${POC_NAME}
Lifecycle   = Persistent
"""

                            }


                            /*
                             * ====================================================
                             * DISPLAY DISCOVERED INFRASTRUCTURE
                             * ====================================================
                             */


                            echo """
==================================================
INFRASTRUCTURE DISCOVERED SUCCESSFULLY
==================================================

Application Server

Public IP  : ${env.APP_PUBLIC_IP}
Private IP : ${env.APP_PRIVATE_IP}


Shared Database

Private IP : ${env.DB_PRIVATE_IP}


Environment

Jenkins Environment : ${TARGET_ENV}
AWS Tag Environment : ${awsEnvironment}


Application Version

${APP_VERSION}

==================================================
"""

                        }

                    }

                }

            }


            /*
             * ========================================================
             * PIPELINE PLACEHOLDER
             *
             * Add future stages below.
             * ========================================================
             */

            stage('Pipeline Validation Complete') {

                steps {

                    echo "Infrastructure discovery completed successfully."

                }

            }

        }


        /*
         * ============================================================
         * POST ACTIONS
         * ============================================================
         */

        post {


            always {

                cleanWs()

            }


            success {

                echo """

==================================================
PIPELINE COMPLETED SUCCESSFULLY
==================================================

Environment : ${TARGET_ENV}
Version     : ${APP_VERSION}

==================================================

"""

            }


            failure {

                echo """

==================================================
PIPELINE FAILED
==================================================

Environment : ${TARGET_ENV}

Check the Jenkins stage logs.

==================================================

"""

            }

        }

    }

}