def call(Map config = [:]) {

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
    def environments       = config.environments ?: [
        'dev': 'dev',
        'qa' : 'qa'
    ]

    pipeline {

        agent any


        parameters {

            choice(
                name: 'ENVIRONMENT',
                choices: ['dev', 'qa'],
                description: 'Select deployment environment'
            )

            string(
                name: 'VERSION',
                defaultValue: '1.0.0',
                description: 'Docker image semantic version'
            )
        }


        environment {

            PROJECT_NAME = "${projectName}"

            AWS_REGION = "${awsRegion}"
        }


        stages {


            /*
             * ========================================================
             * VALIDATE PARAMETERS
             * ========================================================
             */

            stage('Validate Parameters') {

                steps {

                    script {

                        if (!params.VERSION?.trim()) {
                            error("VERSION cannot be empty")
                        }

                        if (!(params.ENVIRONMENT in ['dev', 'qa'])) {
                            error("Invalid ENVIRONMENT: ${params.ENVIRONMENT}")
                        }

                        env.AWS_TAG_ENVIRONMENT = params.ENVIRONMENT

                        echo """
==================================================
PIPELINE CONFIGURATION
==================================================

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}
AWS Region  : ${awsRegion}

==================================================
"""
                    }
                }
            }


            /*
             * ========================================================
             * CLONE APPLICATION
             * ========================================================
             */

            stage('Clone Application') {

                steps {

                    cleanWs()

                    checkout([
                        $class: 'GitSCM',

                        branches: [[name: '*/main']],

                        userRemoteConfigs: [[
                            credentialsId: gitCredentials,
                            url: applicationRepo
                        ]]
                    ])

                    sh '''
                        set -e

                        echo "=========================================="
                        echo "APPLICATION FILES"
                        echo "=========================================="

                        ls -la

                        test -f docker-compose.yaml
                        test -f docker-compose-deploy.yaml
                        test -f .env

                        echo ""
                        echo "ALL REQUIRED FILES FOUND"
                    '''
                }
            }


            /*
             * ========================================================
             * DISCOVER INFRASTRUCTURE
             * ========================================================
             */

            stage('Discover Infrastructure') {

                steps {

                    script {

                        withCredentials([
                            [
                                $class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: awsCredentials
                            ]
                        ]) {

                            sh """
                                set -e

                                echo "=========================================="
                                echo "VERIFY AWS AUTHENTICATION"
                                echo "=========================================="

                                aws sts get-caller-identity
                            """


                            /*
                             * APP PUBLIC IP
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
                             * APP PRIVATE IP
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


                           

                            if (!env.APP_PUBLIC_IP) {
                                error("Application EC2 Public IP was not found")
                            }

                            if (!env.APP_PRIVATE_IP) {
                                error("Application EC2 Private IP was not found")
                            }

                            


                            echo """
==================================================
INFRASTRUCTURE DISCOVERED
==================================================

App Public IP  : ${env.APP_PUBLIC_IP}
App Private IP : ${env.APP_PRIVATE_IP}

==================================================
"""
                        }
                    }
                }
            }


            
            stage('Configure Application Environment') {
    steps {
        sh """
            set -e

            echo "=========================================="
            echo "CONFIGURE APPLICATION .ENV"
            echo "=========================================="

            echo "App Public IP : ${env.APP_PUBLIC_IP}"

            sed -i \
                -e "s|{{APP_PUBLIC_IP}}|${env.APP_PUBLIC_IP}|g" \
                .env

            echo ""
            echo "Configured application environment:"
            grep -E '^(DB_HOST|ALLOWED_HOSTS|CORS_ALLOWED_ORIGINS|FRONTEND_URL)=' .env
        """
    }
}

            /*
             * ========================================================
             * BUILD DOCKER IMAGES
             * ========================================================
             */

            stage('Build Docker Images') {

                steps {

                    sh """
                        set -e

                        echo "=========================================="
                        echo "BUILD DOCKER IMAGES"
                        echo "=========================================="

                        export VERSION=${params.VERSION}

                        docker compose \
                            --env-file .env \
                            -f docker-compose.yaml \
                            build

                        echo ""

                        echo "=========================================="
                        echo "BUILT IMAGES"
                        echo "=========================================="

                        docker images | grep "${projectName}" || true
                    """
                }
            }


            /*
             * ========================================================
             * PUSH DOCKER IMAGES
             * ========================================================
             */

            stage('Push Docker Images') {

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

                            echo "=========================================="
                            echo "DOCKER LOGIN"
                            echo "=========================================="

                            echo "\$DOCKER_PASSWORD" | docker login \
                                -u "\$DOCKER_USERNAME" \
                                --password-stdin


                            echo ""

                            echo "=========================================="
                            echo "PUSH BACKEND"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-backend:${params.VERSION}


                            echo ""

                            echo "=========================================="
                            echo "PUSH FRONTEND"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-frontend:${params.VERSION}


                            echo ""

                            echo "=========================================="
                            echo "DOCKER IMAGES PUSHED SUCCESSFULLY"
                            echo "=========================================="
                        """
                    }
                }
            }


            /*
             * ========================================================
             * DEPLOY APPLICATION
             * ========================================================
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
             * ====================================================
             * VERIFY SSH CONNECTION
             * ====================================================
             */

            sh """
                set -e

                echo "=========================================="
                echo "VERIFY SSH CONNECTION"
                echo "=========================================="

                sshpass -p "\$SSH_PASSWORD" ssh \
                    -o StrictHostKeyChecking=no \
                    -o UserKnownHostsFile=/dev/null \
                    -o ConnectTimeout=10 \
                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                    "hostname && whoami"
            """


            /*
             * ====================================================
             * COPY DEPLOYMENT FILES
             * ====================================================
             */

            sh """
                set -e

                echo "=========================================="
                echo "COPY DEPLOYMENT FILES"
                echo "=========================================="

                sshpass -p "\$SSH_PASSWORD" ssh \
                    -o StrictHostKeyChecking=no \
                    -o UserKnownHostsFile=/dev/null \
                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                    "mkdir -p /home/ec2-user/erp"

                sshpass -p "\$SSH_PASSWORD" scp \
                    -o StrictHostKeyChecking=no \
                    -o UserKnownHostsFile=/dev/null \
                    .env \
                    docker-compose-deploy.yaml \
                    "\$SSH_USER@${env.APP_PRIVATE_IP}:/home/ec2-user/erp/"
            """


            /*
             * ====================================================
             * VERIFY DEPLOYMENT FILES
             * ====================================================
             */

            sh """
                set -e

                echo "=========================================="
                echo "VERIFY DEPLOYMENT FILES"
                echo "=========================================="

                sshpass -p "\$SSH_PASSWORD" ssh \
                    -o StrictHostKeyChecking=no \
                    -o UserKnownHostsFile=/dev/null \
                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                    "cd /home/ec2-user/erp && \
                     test -f .env && \
                     test -f docker-compose-deploy.yaml && \
                     echo 'Deployment files verified'"
            """


            /*
             * ====================================================
             * DEPLOY APPLICATION
             * ====================================================
             */

            sh """
                set -e

                echo "=========================================="
                echo "DEPLOY APPLICATION ON APP EC2"
                echo "=========================================="

                sshpass -p "\$SSH_PASSWORD" ssh \
                    -o StrictHostKeyChecking=no \
                    -o UserKnownHostsFile=/dev/null \
                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                    "DOCKER_USERNAME='\$DOCKER_USERNAME' \
                     DOCKER_PASSWORD='\$DOCKER_PASSWORD' \
                     bash -s" <<'REMOTE_SCRIPT'

set -e

cd /home/ec2-user/erp

export VERSION="${params.VERSION}"


echo "=========================================="
echo "DOCKER LOGIN"
echo "=========================================="

echo "\$DOCKER_PASSWORD" | docker login \
    -u "\$DOCKER_USERNAME" \
    --password-stdin


echo "=========================================="
echo "VERIFY IMAGE VERSION"
echo "=========================================="

echo "VERSION=\$VERSION"


echo "=========================================="
echo "PULL DOCKER IMAGES"
echo "=========================================="

docker compose \
    --env-file .env \
    -f docker-compose-deploy.yaml \
    pull


echo "=========================================="
echo "START APPLICATION"
echo "=========================================="

docker compose \
    --env-file .env \
    -f docker-compose-deploy.yaml \
    up -d


echo "=========================================="
echo "RUNNING CONTAINERS"
echo "=========================================="

docker compose \
    --env-file .env \
    -f docker-compose-deploy.yaml \
    ps


echo "=========================================="
echo "BACKEND HEALTH CHECK"
echo "=========================================="

docker compose \
    --env-file .env \
    -f docker-compose-deploy.yaml \
    ps --status running


echo "=========================================="
echo "DOCKER LOGOUT"
echo "=========================================="

docker logout


echo "=========================================="
echo "CLEAN UNUSED DOCKER IMAGES"
echo "=========================================="

docker image prune -af


echo "=========================================="
echo "DEPLOYMENT COMPLETED SUCCESSFULLY"
echo "=========================================="

REMOTE_SCRIPT
            """


            /*
             * ====================================================
             * SUCCESS MESSAGE
             * ====================================================
             */

            echo """
==========================================
APPLICATION DEPLOYED SUCCESSFULLY
==========================================

App Private IP : ${env.APP_PRIVATE_IP}
App Public IP  : ${env.APP_PUBLIC_IP}

Version        : ${params.VERSION}

==========================================
"""
        }
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

Backend Image:

${dockerRegistry}/${projectName}-backend:${params.VERSION}

Frontend Image:

${dockerRegistry}/${projectName}-frontend:${params.VERSION}

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

                sh '''
                    set +e

                    echo "=========================================="
                    echo "JENKINS AGENT CLEANUP"
                    echo "=========================================="

                    docker image prune -af || true

                    docker logout || true
                '''

                cleanWs(
                    deleteDirs: true,
                    disableDeferredWipeout: true
                )

                echo "Jenkins workspace cleanup completed."
            }
        }
    }
}