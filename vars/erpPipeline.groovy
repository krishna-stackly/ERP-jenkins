def call(Map config = [:]) {

    /*
     * ============================================================
     * CONFIGURATION
     * ============================================================
     */

    def accountId          = config.accountId
    def awsRegion          = config.awsRegion
    def projectName        = config.projectName
    def pocName            = config.pocName

    def awsCredentials     = config.awsCredentials
    def gitCredentials     = config.gitCredentials
    def dockerCredentials  = config.dockerCredentials

    def dockerRegistry     = config.dockerRegistry
    def appEc2Credentials  = config.appEc2Credentials

    def applicationRepo    = config.applicationRepo


    /*
     * ============================================================
     * VALIDATE REQUIRED CONFIGURATION
     * ============================================================
     */

    if (!accountId) {
        error("accountId is required")
    }

    if (!awsRegion) {
        error("awsRegion is required")
    }

    if (!projectName) {
        error("projectName is required")
    }

    if (!awsCredentials) {
        error("awsCredentials is required")
    }

    if (!gitCredentials) {
        error("gitCredentials is required")
    }

    if (!dockerCredentials) {
        error("dockerCredentials is required")
    }

    if (!dockerRegistry) {
        error("dockerRegistry is required")
    }

    if (!appEc2Credentials) {
        error("appEc2Credentials is required")
    }

    if (!applicationRepo) {
        error("applicationRepo is required")
    }


    /*
     * ============================================================
     * PIPELINE
     * ============================================================
     */

    pipeline {

        agent {

            label 'jenkins-agent-007'

        }


        /*
         * ========================================================
         * BUILD PARAMETERS
         * ========================================================
         */

        parameters {

            choice(
                name: 'ENVIRONMENT',
                choices: [
                    'dev',
                    'qa'
                ],
                description: 'Select deployment environment'
            )

            string(
                name: 'VERSION',
                defaultValue: '1.0.0',
                description: 'Semantic Docker image version'
            )

        }


        /*
         * ========================================================
         * PIPELINE OPTIONS
         * ========================================================
         */

        options {

            timestamps()

            disableConcurrentBuilds()

        }


        /*
         * ========================================================
         * ENVIRONMENT VARIABLES
         * ========================================================
         */

        environment {

            PROJECT_NAME = "${projectName}"

            AWS_REGION = "${awsRegion}"

        }


        stages {


            /*
             * ====================================================
             * STAGE 1
             * DISCOVER INFRASTRUCTURE
             * ====================================================
             */

            stage('Discover Infrastructure') {

                steps {

                    script {

                        withCredentials([

                            [$class: 'AmazonWebServicesCredentialsBinding',
                             credentialsId: awsCredentials]

                        ]) {

                            sh """
                                set -e

                                echo "=========================================="
                                echo "VERIFYING AWS AUTHENTICATION"
                                echo "=========================================="

                                aws sts get-caller-identity

                                echo ""
                                echo "=========================================="
                                echo "INFRASTRUCTURE DISCOVERY"
                                echo "=========================================="

                                echo "AWS Region          : ${awsRegion}"
                                echo "Project             : ${projectName}"
                                echo "Jenkins Environment : ${params.ENVIRONMENT}"
                                echo "Created By          : ${pocName}"

                                echo "=========================================="
                            """


                            /*
                             * --------------------------------------
                             * APP PUBLIC IP
                             * --------------------------------------
                             */

                            env.APP_PUBLIC_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${params.ENVIRONMENT}" \
                                            "Name=tag:component,Values=app" \
                                            "Name=tag:Created_by,Values=${pocName}" \
                                            "Name=tag:State,Values=non-persistent" \
                                            "Name=instance-state-name,Values=running" \
                                        --query 'Reservations[].Instances[].PublicIpAddress' \
                                        --output text
                                """,

                                returnStdout: true

                            ).trim()


                            /*
                             * --------------------------------------
                             * APP PRIVATE IP
                             * --------------------------------------
                             */

                            env.APP_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${params.ENVIRONMENT}" \
                                            "Name=tag:component,Values=app" \
                                            "Name=tag:Created_by,Values=${pocName}" \
                                            "Name=tag:State,Values=non-persistent" \
                                            "Name=instance-state-name,Values=running" \
                                        --query 'Reservations[].Instances[].PrivateIpAddress' \
                                        --output text
                                """,

                                returnStdout: true

                            ).trim()


                            /*
                             * --------------------------------------
                             * DATABASE PRIVATE IP
                             * --------------------------------------
                             */

                            env.DB_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${params.ENVIRONMENT}" \
                                            "Name=tag:component,Values=database" \
                                            "Name=tag:Created_by,Values=${pocName}" \
                                            "Name=tag:Lifecycle,Values=Persistent" \
                                            "Name=instance-state-name,Values=running" \
                                        --query 'Reservations[].Instances[].PrivateIpAddress' \
                                        --output text
                                """,

                                returnStdout: true

                            ).trim()


                            /*
                             * --------------------------------------
                             * VALIDATE DISCOVERED VALUES
                             * --------------------------------------
                             */

                            if (!env.APP_PUBLIC_IP) {

                                error("""
Application EC2 Public IP was not found.

Expected tags:

Project     = ${projectName}
Environment = ${params.ENVIRONMENT}
component   = app
Created_by  = ${pocName}
State       = non-persistent
""")

                            }


                            if (!env.APP_PRIVATE_IP) {

                                error("""
Application EC2 Private IP was not found.
""")

                            }


                            if (!env.DB_PRIVATE_IP) {

                                error("""
Shared Database Private IP was not found.

Expected tags:

Project     = ${projectName}
Environment = ${params.ENVIRONMENT}
component   = database
Created_by  = ${pocName}
Lifecycle   = Persistent
""")

                            }


                            echo """
==================================================
INFRASTRUCTURE DISCOVERED
==================================================

App Public IP  : ${env.APP_PUBLIC_IP}
App Private IP : ${env.APP_PRIVATE_IP}
DB Private IP  : ${env.DB_PRIVATE_IP}

==================================================
"""

                        }

                    }

                }

            }


            /*
             * ====================================================
             * STAGE 2
             * BUILD APPLICATION IMAGES
             * ====================================================
             */

            stage('Build Application Images') {

                steps {

                    cleanWs()

                    withCredentials([

                        usernamePassword(
                            credentialsId: gitCredentials,
                            usernameVariable: 'GIT_USERNAME',
                            passwordVariable: 'GIT_TOKEN'
                        )

                    ]) {

                        sh """
                            set -e

                            echo "=========================================="
                            echo "CLONING APPLICATION REPOSITORY"
                            echo "=========================================="

                            git clone \
                                https://\$GIT_USERNAME:\$GIT_TOKEN@github.com/krishna-stackly/ERP.git \
                                application

                            cd application

                            echo "Repository cloned successfully"

                            echo ""
                            echo "=========================================="
                            echo "GENERATE BUILD ENVIRONMENT"
                            echo "=========================================="

                            cat > .env <<EOF
DEBUG=False

SECRET_KEY=Ghgihtb=S(v+AP+106nO^a83wccGJh#CuNP_Fiqu)V%A7uEG^Z

DB_ENGINE=django.db.backends.mysql

DB_HOST=${env.DB_PRIVATE_IP}

DB_PORT=3306

DB_NAME=erp_db

DB_USER=erp_user

DB_PASSWORD=erp_pass

ALLOWED_HOSTS=localhost,127.0.0.1,backend-qa.internal,${env.APP_PUBLIC_IP}

CORS_ALLOWED_ORIGINS=http://${env.APP_PUBLIC_IP}:3000

FRONTEND_URL=http://${env.APP_PUBLIC_IP}:3000

EMAIL_BACKEND=django.core.mail.backends.console.EmailBackend

EMAIL_PORT=587

EMAIL_USE_TLS=True

EMAIL_HOST=smtp.gmail.com

EMAIL_HOST_USER=

EMAIL_HOST_PASSWORD=

MEDIA_URL=/media/

PORT=8000

IMAGE_VERSION=${params.VERSION}
EOF

                            echo "Environment file created successfully"

                            echo ""
                            echo "=========================================="
                            echo "BUILD DOCKER IMAGES"
                            echo "=========================================="

                            docker compose build

                            echo ""
                            echo "=========================================="
                            echo "TAGGED IMAGES"
                            echo "=========================================="

                            docker images | grep "${dockerRegistry}/${projectName}" || true
                        """

                    }

                }

            }


            /*
             * ====================================================
             * STAGE 3
             * PUSH APPLICATION IMAGES
             * ====================================================
             */

            stage('Push Application Images') {

                steps {

                    withCredentials([

                        usernamePassword(
                            credentialsId: dockerCredentials,
                            usernameVariable: 'DOCKER_USERNAME',
                            passwordVariable: 'DOCKER_PASSWORD'
                        )

                    ]) {

                        sh """
                            set -e

                            cd application

                            echo "=========================================="
                            echo "LOGIN TO DOCKER HUB"
                            echo "=========================================="

                            echo "\$DOCKER_PASSWORD" | \
                                docker login \
                                -u "\$DOCKER_USERNAME" \
                                --password-stdin

                            echo ""
                            echo "=========================================="
                            echo "PUSH BACKEND IMAGE"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-backend:${params.VERSION}

                            echo ""
                            echo "=========================================="
                            echo "PUSH FRONTEND IMAGE"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-frontend:${params.VERSION}

                            echo ""
                            echo "=========================================="
                            echo "IMAGES PUSHED SUCCESSFULLY"
                            echo "=========================================="

                        """

                    }

                }

            }


            /*
             * ====================================================
             * STAGE 4
             * DEPLOY APPLICATION TO APP EC2
             * ====================================================
             */

            stage('Deploy Application to App EC2') {

                steps {

                    withCredentials([

                        usernamePassword(
                            credentialsId: appEc2Credentials,
                            usernameVariable: 'SSH_USER',
                            passwordVariable: 'SSH_PASSWORD'
                        ),

                        usernamePassword(
                            credentialsId: dockerCredentials,
                            usernameVariable: 'DOCKER_USERNAME',
                            passwordVariable: 'DOCKER_PASSWORD'
                        )

                    ]) {

                        /*
                         * ------------------------------------------
                         * VERIFY SSH
                         * ------------------------------------------
                         */

                        sh """
                            set -e

                            echo "=========================================="
                            echo "DEPLOY APPLICATION"
                            echo "=========================================="

                            echo "App Public IP  : ${env.APP_PUBLIC_IP}"
                            echo "App Private IP : ${env.APP_PRIVATE_IP}"
                            echo "DB Private IP  : ${env.DB_PRIVATE_IP}"
                            echo "Version        : ${params.VERSION}"

                            echo ""
                            echo "=========================================="
                            echo "VERIFY SSH CONNECTION"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                -o ConnectTimeout=10 \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "hostname && whoami"

                            echo ""
                            echo "=========================================="
                            echo "CREATE DEPLOYMENT DIRECTORY"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "mkdir -p /home/ec2-user/erp"
                        """


                        /*
                         * ------------------------------------------
                         * GENERATE RUNTIME ENVIRONMENT FILE
                         * ------------------------------------------
                         */

                        sh """
                            set -e

                            echo "=========================================="
                            echo "GENERATE RUNTIME ENVIRONMENT FILE"
                            echo "=========================================="

                            cat > .env.runtime <<EOF
DEBUG=False

SECRET_KEY=Ghgihtb=S(v+AP+106nO^a83wccGJh#CuNP_Fiqu)V%A7uEG^Z

DB_ENGINE=django.db.backends.mysql

DB_HOST=${env.DB_PRIVATE_IP}

DB_PORT=3306

DB_NAME=erp_db

DB_USER=erp_user

DB_PASSWORD=erp_pass

ALLOWED_HOSTS=localhost,127.0.0.1,${env.APP_PUBLIC_IP}

CORS_ALLOWED_ORIGINS=http://${env.APP_PUBLIC_IP}:3000

FRONTEND_URL=http://${env.APP_PUBLIC_IP}:3000

PORT=8000

IMAGE_VERSION=${params.VERSION}
EOF

                            echo "Runtime environment file generated successfully"
                        """


                        /*
                         * ------------------------------------------
                         * COPY DEPLOYMENT FILES
                         * ------------------------------------------
                         */

                        sh """
                            set -e

                            echo "=========================================="
                            echo "COPY DEPLOYMENT FILES"
                            echo "=========================================="

                            cd application

                            sshpass -p "\$SSH_PASSWORD" scp \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                ../.env.runtime \
                                docker-compose-deploy.yaml \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}:/home/ec2-user/erp/"
                        """


                        /*
                         * ------------------------------------------
                         * DEPLOY ON APP EC2
                         * ------------------------------------------
                         */

                        sh """
                            set -e

                            echo "=========================================="
                            echo "DEPLOY APPLICATION ON EC2"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "cd /home/ec2-user/erp && \
                                echo '${DOCKER_PASSWORD}' | docker login -u '${DOCKER_USERNAME}' --password-stdin && \
                                docker compose --env-file .env.runtime -f docker-compose-deploy.yaml pull && \
                                docker compose --env-file .env.runtime -f docker-compose-deploy.yaml up -d && \
                                docker compose --env-file .env.runtime -f docker-compose-deploy.yaml ps && \
                                docker image prune -af"
                        """


                        echo """
==================================================
APPLICATION DEPLOYED SUCCESSFULLY
==================================================

App EC2       : ${env.APP_PRIVATE_IP}
App Public IP : ${env.APP_PUBLIC_IP}
DB Private IP : ${env.DB_PRIVATE_IP}

Version       : ${params.VERSION}

==================================================
"""

                    }

                }

            }


            /*
             * ====================================================
             * STAGE 5
             * CLEANUP JENKINS AGENT
             * ====================================================
             */

            stage('Cleanup Jenkins Agent') {

                steps {

                    sh """
                        set +e

                        echo "=========================================="
                        echo "CLEANUP JENKINS AGENT"
                        echo "=========================================="

                        echo "Removing unused Docker images"

                        docker image prune -af || true

                        echo ""

                        echo "Removing unused Docker resources"

                        docker system prune -af || true

                        echo ""

                        echo "Docker cleanup completed"

                        echo "=========================================="
                    """

                    cleanWs()

                }

            }

        }


        /*
         * ========================================================
         * POST ACTIONS
         * ========================================================
         */

        post {

            success {

                echo """
==================================================
PIPELINE COMPLETED SUCCESSFULLY
==================================================

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}

==================================================
"""

            }


            failure {

                echo """
==================================================
PIPELINE FAILED
==================================================

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}

Check Jenkins console logs.

==================================================
"""

            }


            always {

                cleanWs(
                    deleteDirs: true,
                    disableDeferredWipeout: true
                )

            }

        }

    }

}

