def call(Map config = [:]) {


/*
 * ========================================================
 * CONFIGURATION
 * ========================================================
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
def environments       = config.environments


pipeline {

    agent any


    parameters {

        choice(
            name: 'ENVIRONMENT',
            choices: environments.keySet().join('\n'),
            description: 'Select deployment environment'
        )

        string(
            name: 'VERSION',
            defaultValue: 'latest',
            description: 'Docker image version'
        )
    }


    environment {

        PROJECT_NAME = "${projectName}"

        AWS_REGION = "${awsRegion}"
    }


    stages {


        /*
         * ========================================================
         * VALIDATE PIPELINE
         * ========================================================
         */

        stage('Validate Parameters') {

            steps {

                script {

                    if (!params.VERSION?.trim()) {

                        error("VERSION cannot be empty")
                    }


                    echo """


==================================================
PIPELINE CONFIGURATION
======================

Project     : ${projectName}

Environment : ${params.ENVIRONMENT}

Version     : ${params.VERSION}

Region      : ${awsRegion}

==================================================
"""
}
}
}


        /*
         * ========================================================
         * CLONE APPLICATION
         *
         * IMPORTANT:
         * Clone directly into Jenkins workspace.
         * No dir('application').
         * ========================================================
         */

        stage('Clone Application') {

            steps {

                cleanWs()

                git(

                    branch: 'main',

                    credentialsId: gitCredentials,

                    url: applicationRepo
                )


                sh """
                    set -e

                    echo "=========================================="
                    echo "CURRENT WORKSPACE"
                    echo "=========================================="

                    pwd

                    echo ""

                    echo "=========================================="
                    echo "APPLICATION FILES"
                    echo "=========================================="

                    ls -la


                    echo ""

                    echo "=========================================="
                    echo "VERIFY REQUIRED BUILD FILES"
                    echo "=========================================="

                    test -f docker-compose.yaml
                    test -f .env


                    echo ""

                    echo "=========================================="
                    echo "VERIFY DEPLOYMENT FILE"
                    echo "=========================================="

                    test -f docker-compose-deploy.yaml


                    echo ""

                    echo "Application repository cloned successfully."
                """
            }
        }


        /*
         * ========================================================
         * FETCH INFRASTRUCTURE INFORMATION
         *
         * Keep your existing Terraform / AWS logic here.
         * It should populate:
         *
         * APP_PUBLIC_IP
         * APP_PRIVATE_IP
         * DB_PRIVATE_IP
         * ========================================================
         */

        stage('Get Infrastructure Details') {

            steps {

                script {

                    /*
                     * KEEP YOUR EXISTING LOGIC HERE
                     *
                     * Example:
                     *
                     * env.APP_PUBLIC_IP
                     * env.APP_PRIVATE_IP
                     * env.DB_PRIVATE_IP
                     */


                    echo """


==================================================
INFRASTRUCTURE DETAILS
======================

Application Public IP  : ${env.APP_PUBLIC_IP}

Application Private IP : ${env.APP_PRIVATE_IP}

Database Private IP    : ${env.DB_PRIVATE_IP}

==================================================
"""
}
}
}


        /*
         * ========================================================
         * BUILD APPLICATION
         *
         * Uses:
         *
         * docker-compose.yaml
         * .env
         * ========================================================
         */

        stage('Build Docker Images') {

            steps {

                sh """
                    set -e

                    echo "=========================================="
                    echo "BUILDING DOCKER IMAGES"
                    echo "=========================================="

                    echo "Working Directory:"
                    pwd


                    echo ""

                    echo "Build Configuration:"
                    echo "docker-compose.yaml"
                    echo ".env"


                    export VERSION=${params.VERSION}


                    docker compose \
                        -f docker-compose.yaml \
                        build


                    echo ""

                    echo "=========================================="
                    echo "DOCKER IMAGES"
                    echo "=========================================="

                    docker images | grep "${projectName}" || true


                    echo ""

                    echo "DOCKER BUILD COMPLETED"
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
                    """
                }
            }
        }


        /*
         * ========================================================
         * DEPLOY APPLICATION TO APP EC2
         *
         * Uses:
         *
         * docker-compose-deploy.yaml
         * .env.runtime
         *
         * SSH uses APP_PRIVATE_IP
         * ========================================================
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
                            echo "VERIFY DEPLOYMENT FILE"
                            echo "=========================================="

                            test -f docker-compose-deploy.yaml


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
                            echo "CREATE APP DEPLOYMENT DIRECTORY"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "mkdir -p /home/\$SSH_USER/erp"


                            echo ""

                            echo "=========================================="
                            echo "GENERATE RUNTIME ENVIRONMENT FILE"
                            echo "=========================================="


                            cat > .env.runtime <<EOF


DEBUG=False

SECRET_KEY=YOUR_SECRET_KEY

DB_ENGINE=django.db.backends.mysql

DB_HOST=${env.DB_PRIVATE_IP}

DB_PORT=3306

DB_NAME=erp_db

DB_USER=erp_user

DB_PASSWORD=YOUR_DB_PASSWORD

ALLOWED_HOSTS=localhost,127.0.0.1,${env.APP_PUBLIC_IP}

CORS_ALLOWED_ORIGINS=http://${env.APP_PUBLIC_IP}:3000

FRONTEND_URL=http://${env.APP_PUBLIC_IP}:3000

PORT=8000

VERSION=${params.VERSION}
EOF


                            echo ""

                            echo "Runtime environment created successfully."


                            echo ""

                            echo "=========================================="
                            echo "COPY DEPLOYMENT FILES"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" scp \
                                -o StrictHostKeyChecking=no \
                                .env.runtime \
                                docker-compose-deploy.yaml \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}:/home/\$SSH_USER/erp/"


                            echo ""

                            echo "Deployment files copied successfully."


                            echo ""

                            echo "=========================================="
                            echo "DEPLOY DOCKER CONTAINERS"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "DOCKER_USERNAME='\$DOCKER_USERNAME' \
                                 DOCKER_PASSWORD='\$DOCKER_PASSWORD' \
                                 bash -s" <<'REMOTE_SCRIPT'


set -e

DEPLOY_DIR="/home/$USER/erp"

cd "$DEPLOY_DIR"

echo "=========================================="
echo "VERIFY DEPLOYMENT FILES"
echo "=========================================="

ls -la

test -f .env.runtime

test -f docker-compose-deploy.yaml

echo ""

echo "=========================================="
echo "DOCKER LOGIN"
echo "=========================================="

echo "$DOCKER_PASSWORD" | docker login 
-u "$DOCKER_USERNAME" 
--password-stdin

echo ""

echo "=========================================="
echo "CURRENT DOCKER CONTAINERS"
echo "=========================================="

docker ps -a

echo ""

echo "=========================================="
echo "PULL LATEST APPLICATION IMAGES"
echo "=========================================="

docker compose 
--env-file .env.runtime 
-f docker-compose-deploy.yaml 
pull

echo ""

echo "=========================================="
echo "START APPLICATION"
echo "=========================================="

docker compose 
--env-file .env.runtime 
-f docker-compose-deploy.yaml 
up -d

echo ""

echo "=========================================="
echo "APPLICATION STATUS"
echo "=========================================="

docker compose 
--env-file .env.runtime 
-f docker-compose-deploy.yaml 
ps

echo ""

echo "=========================================="
echo "RUNNING CONTAINERS"
echo "=========================================="

docker ps

echo ""

echo "=========================================="
echo "CLEAN UNUSED DOCKER IMAGES"
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
===============================

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
===============

Project     : ${env.PROJECT_NAME}

Environment : ${params.ENVIRONMENT}

Version     : ${params.VERSION}

Check the Jenkins stage logs.

==================================================
"""
}


        always {

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
