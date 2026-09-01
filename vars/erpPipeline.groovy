def call(Map config = [:]) {


    /*
     * ============================================================
     * CONFIGURATION
     * ============================================================
     */

    def projectName = config.projectName
    def pocName = config.pocName

    def awsRegion = config.awsRegion
    def awsCredentials = config.awsCredentials

    def applicationRepo = config.applicationRepo
    def gitCredentials = config.gitCredentials

    def dockerCredentials = config.dockerCredentials
    def dockerRegistry = config.dockerRegistry

    def appEc2Credentials = config.appEc2Credentials

    def environments = config.environments ?: [:]


    /*
     * ============================================================
     * JENKINS PARAMETERS
     * ============================================================
     */

    def parameterChoices = environments.keySet().join('\n')


    pipeline {

        agent {
            label 'jenkins-agent-007'
        }


        options {

            timestamps()

            skipDefaultCheckout(true)
        }


        parameters {

            choice(
                name: 'ENVIRONMENT',
                choices: parameterChoices,
                description: 'Select deployment environment'
            )

            string(
                name: 'VERSION',
                defaultValue: '1.0.0',
                description: 'Semantic version (example: 1.0.0)'
            )
        }


        environment {

            PROJECT_NAME = "${projectName}"

            AWS_REGION = "${awsRegion}"

            DOCKER_REGISTRY = "${dockerRegistry}"
        }


        stages {


            /*
             * ====================================================
             * STAGE 1
             * VALIDATE INPUT
             * ====================================================
             */

            stage('Validate Pipeline Input') {

                steps {

                    script {

                        echo """

==================================================
VALIDATING PIPELINE INPUT
==================================================

Project     : ${PROJECT_NAME}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}

==================================================
"""

                        /*
                        if (!(params.VERSION ==~ /^[0-9]+\.[0-9]+\.[0-9]+$/)) {

                            error("""

Invalid semantic version.

Expected format:

1.0.0
1.2.3
2.0.0

""")
                        }
                        */
                    }
                }
            }


            /*
             * ====================================================
             * STAGE 2
             * CLEAN WORKSPACE
             * ====================================================
             */

            stage('Prepare Workspace') {

                steps {

                    cleanWs()

                    echo 'Workspace cleaned successfully'
                }
            }


            /*
             * ====================================================
             * STAGE 3
             * DISCOVER INFRASTRUCTURE
             * ====================================================
             */

            stage('Discover Infrastructure') {

                steps {

                    script {

                        def awsTagEnvironment = environments[params.ENVIRONMENT]

                        if (!awsTagEnvironment) {

                            error(
                                "No AWS tag mapping found for environment: ${params.ENVIRONMENT}"
                            )
                        }


                        withCredentials([

                            [
                                $class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: awsCredentials
                            ]

                        ]) {


                            /*
                             * ----------------------------------------
                             * APP PUBLIC IP
                             * ----------------------------------------
                             */

                            env.APP_PUBLIC_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                      --region ${awsRegion} \
                                      --filters \
                                        "Name=tag:Project,Values=${projectName}" \
                                        "Name=tag:Environment,Values=${awsTagEnvironment}" \
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
                             * ----------------------------------------
                             * APP PRIVATE IP
                             * ----------------------------------------
                             */

                            env.APP_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                      --region ${awsRegion} \
                                      --filters \
                                        "Name=tag:Project,Values=${projectName}" \
                                        "Name=tag:Environment,Values=${awsTagEnvironment}" \
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
                             * ----------------------------------------
                             * DATABASE PRIVATE IP
                             * ----------------------------------------
                             */

                            env.DB_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                      --region ${awsRegion} \
                                      --filters \
                                        "Name=tag:Project,Values=${projectName}" \
                                        "Name=tag:Environment,Values=${awsTagEnvironment}" \
                                        "Name=tag:component,Values=database" \
                                        "Name=tag:Created_by,Values=${pocName}" \
                                        "Name=tag:Lifecycle,Values=Persistent" \
                                        "Name=instance-state-name,Values=running" \
                                      --query 'Reservations[].Instances[].PrivateIpAddress' \
                                      --output text
                                """,

                                returnStdout: true

                            ).trim()
                        }


                        /*
                         * --------------------------------------------
                         * VALIDATE INFRASTRUCTURE
                         * --------------------------------------------
                         */

                        if (!env.APP_PUBLIC_IP) {

                            error('Application EC2 Public IP was not found.')
                        }


                        if (!env.APP_PRIVATE_IP) {

                            error('Application EC2 Private IP was not found.')
                        }


                        if (!env.DB_PRIVATE_IP) {

                            error('Shared Database Private IP was not found.')
                        }


                        echo """

==================================================
INFRASTRUCTURE DISCOVERED
==================================================

Application Public IP  : ${env.APP_PUBLIC_IP}

Application Private IP : ${env.APP_PRIVATE_IP}

Database Private IP    : ${env.DB_PRIVATE_IP}

==================================================
"""
                    }
                }
            }


            /*
             * ====================================================
             * STAGE 4
             * CLONE APPLICATION SOURCE
             * ====================================================
             */

            stage('Clone ERP Application') {

                steps {

                    dir('application') {

                        git(

                            branch: 'main',

                            credentialsId: gitCredentials,

                            url: applicationRepo
                        )
                    }


                    sh """
                        echo "=========================================="
                        echo "APPLICATION REPOSITORY"
                        echo "=========================================="

                        cd application

                        git log -1 --oneline

                        echo ""
                        echo "Repository structure:"

                        find . -maxdepth 2 -type f | sort
                    """
                }
            }


            /*
             * ====================================================
             * STAGE 5
             * PREPARE APPLICATION CONFIGURATION
             * ====================================================
             */

            stage('Prepare Application Configuration') {

                steps {

                    script {

                        sh """
                            set -e

                            cd application

                            echo "=========================================="
                            echo "UPDATING ENVIRONMENT CONFIGURATION"
                            echo "=========================================="

                            if [ ! -f ".env" ]; then

                                echo "ERROR: .env file was not found."

                                exit 1
                            fi


                            sed -i "s|{{APP_PUBLIC_IP}}|${env.APP_PUBLIC_IP}|g" .env

                            sed -i "s|{{DB_PRIVATE_IP}}|${env.DB_PRIVATE_IP}|g" .env


                            echo ""
                            echo "=========================================="
                            echo "VALIDATING PLACEHOLDERS"
                            echo "=========================================="


                            if grep -q '{{' .env; then

                                echo "ERROR: Unresolved placeholders found in .env"

                                grep '{{' .env

                                exit 1
                            fi


                            echo "All placeholders successfully resolved."


                            echo ""
                            echo "=========================================="
                            echo "VERSION"
                            echo "=========================================="

                            echo "VERSION=${params.VERSION}" > .pipeline.env


                            echo "Project : ${env.PROJECT_NAME}"

                            echo "Version : ${params.VERSION}"
                        """
                    }
                }
            }


            /*
             * ====================================================
             * STAGE 6
             * BUILD DOCKER IMAGES
             * ====================================================
             */

            stage('Build Docker Images') {

                steps {

                    sh """
                        set -e

                        cd application

                        echo "=========================================="
                        echo "BUILDING DOCKER IMAGES"
                        echo "=========================================="

                        export VERSION=${params.VERSION}

                        docker compose build


                        echo ""
                        echo "=========================================="
                        echo "BUILT IMAGES"
                        echo "=========================================="

                        docker images | grep "${projectName}" || true
                    """
                }
            }


            /*
             * ====================================================
             * STAGE 7
             * PUSH DOCKER IMAGES
             * ====================================================
             */

            stage('Push Docker Images to Docker Hub') {

                steps {

                    withCredentials([

                        usernamePassword(

                            credentialsId: dockerCredentials,

                            usernameVariable: 'DOCKER_USERNAME',

                            passwordVariable: 'DOCKER_TOKEN'
                        )

                    ]) {

                        sh """
                            set -e


                            echo "=========================================="
                            echo "DOCKER HUB LOGIN"
                            echo "=========================================="

                            echo "\$DOCKER_TOKEN" | docker login \
                                -u "\$DOCKER_USERNAME" \
                                --password-stdin


                            cd application


                            export VERSION=${params.VERSION}


                            echo ""
                            echo "=========================================="
                            echo "PUSHING BACKEND IMAGE"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-backend:${params.VERSION}


                            echo ""
                            echo "=========================================="
                            echo "PUSHING FRONTEND IMAGE"
                            echo "=========================================="

                            docker push \
                                ${dockerRegistry}/${projectName}-frontend:${params.VERSION}


                            echo ""
                            echo "=========================================="
                            echo "DOCKER PUSH COMPLETED"
                            echo "=========================================="

                            echo "Backend:"
                            echo "${dockerRegistry}/${projectName}-backend:${params.VERSION}"

                            echo ""

                            echo "Frontend:"
                            echo "${dockerRegistry}/${projectName}-frontend:${params.VERSION}"
                        """
                    }
                }
            }


            /*
             * ====================================================
             * STAGE 8
             * DEPLOY APPLICATION TO APP EC2
             * ====================================================
             */

            stage('Deploy Application to App EC2') {

                steps {

                    script {

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

                            sh """
                                set -e

                                echo "=========================================="
                                echo "DEPLOY APPLICATION TO APP EC2"
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
                                    -o ConnectTimeout=10 \
                                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                    "hostname && whoami"


                                echo ""
                                echo "SSH CONNECTION SUCCESSFUL"


                                echo ""
                                echo "=========================================="
                                echo "CREATE DEPLOYMENT DIRECTORY"
                                echo "=========================================="

                                sshpass -p "\$SSH_PASSWORD" ssh \
                                    -o StrictHostKeyChecking=no \
                                    "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                    "mkdir -p /home/\$SSH_USER/erp"


                                echo ""
                                echo "=========================================="
                                echo "GENERATE RUNTIME ENV FILE"
                                echo "=========================================="

                                mkdir -p deployment


                                cat > deployment/.env <<EOF
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


                                echo ""
                                echo "=========================================="
                                echo "COPY DEPLOYMENT FILES"
                                echo "=========================================="

                                sshpass -p "\$SSH_PASSWORD" scp \
                                    -o StrictHostKeyChecking=no \
                                    deployment/.env \
                                    application/docker-compose.deploy.yml \
                                    "\$SSH_USER@${env.APP_PUBLIC_IP}:/home/\$SSH_USER/erp/"


                                echo ""
                                echo "=========================================="
                                echo "DEPLOY DOCKER CONTAINERS"
                                echo "=========================================="

                                sshpass -p "\$SSH_PASSWORD" ssh \
                                    -o StrictHostKeyChecking=no \
                                    "\$SSH_USER@${env.APP_PUBLIC_IP}" \
                                    "DOCKER_USERNAME='\$DOCKER_USERNAME' \
                                     DOCKER_PASSWORD='\$DOCKER_PASSWORD' \
                                     bash -s" <<'REMOTE_SCRIPT'

set -e

cd /home/$USER/erp


echo "=========================================="
echo "DOCKER LOGIN"
echo "=========================================="

echo "$DOCKER_PASSWORD" | docker login \
    -u "$DOCKER_USERNAME" \
    --password-stdin


echo ""
echo "=========================================="
echo "CURRENT DOCKER CONTAINERS"
echo "=========================================="

docker ps -a


echo ""
echo "=========================================="
echo "PULLING LATEST APPLICATION IMAGES"
echo "=========================================="

docker compose \
    -f docker-compose.deploy.yml \
    pull


echo ""
echo "=========================================="
echo "STARTING APPLICATION"
echo "=========================================="

docker compose \
    -f docker-compose.deploy.yml \
    up -d


echo ""
echo "=========================================="
echo "RUNNING CONTAINERS"
echo "=========================================="

docker ps


echo ""
echo "=========================================="
echo "CLEANING UNUSED DOCKER IMAGES"
echo "=========================================="

docker image prune -af


echo ""
echo "=========================================="
echo "DEPLOYMENT COMPLETED"
echo "=========================================="

REMOTE_SCRIPT


                                echo ""
                                echo "=========================================="
                                echo "APPLICATION DEPLOYED SUCCESSFULLY"
                                echo "=========================================="
                            """
                        }
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

Project     : ${env.PROJECT_NAME}

Environment : ${params.ENVIRONMENT}

Version     : ${params.VERSION}

Published Images:

${dockerRegistry}/${projectName}-backend:${params.VERSION}

${dockerRegistry}/${projectName}-frontend:${params.VERSION}

==================================================
"""
            }


            failure {

                echo """

==================================================
PIPELINE FAILED
==================================================

Project     : ${env.PROJECT_NAME}

Environment : ${params.ENVIRONMENT}

Version     : ${params.VERSION}

Check the Jenkins stage logs.

==================================================
"""
            }


            always {


                script {

                    echo 'Starting Jenkins agent cleanup...'
                }


                sh """
                    set +e


                    echo "=========================================="
                    echo "DOCKER CLEANUP"
                    echo "=========================================="


                    echo "Removing ERP containers..."

                    docker ps -aq \
                        --filter "name=${projectName}" \
                        | xargs -r docker rm -f


                    echo ""
                    echo "Removing ERP Docker images..."


                    docker images \
                        "${dockerRegistry}/${projectName}-backend" \
                        -q \
                        | xargs -r docker rmi -f


                    docker images \
                        "${dockerRegistry}/${projectName}-frontend" \
                        -q \
                        | xargs -r docker rmi -f


                    echo ""
                    echo "Removing dangling Docker images..."

                    docker image prune -f


                    echo ""
                    echo "Removing Docker build cache..."

                    docker builder prune -af


                    echo ""
                    echo "Docker logout..."

                    docker logout || true


                    echo ""
                    echo "=========================================="
                    echo "DOCKER CLEANUP COMPLETED"
                    echo "=========================================="
                """


                cleanWs(

                    deleteDirs: true,

                    disableDeferredWipeout: true
                )


                echo 'Jenkins workspace cleanup completed.'
            }
        }
    }
}