def call(Map config = [:]) {


def accountId         = config.accountId
def awsRegion         = config.awsRegion
def projectName       = config.projectName
def pocName           = config.pocName
def awsCredentials    = config.awsCredentials
def gitCredentials    = config.gitCredentials
def dockerCredentials = config.dockerCredentials
def dockerRegistry    = config.dockerRegistry
def appEc2Credentials = config.appEc2Credentials
def applicationRepo   = config.applicationRepo
def environments      = config.environments ?: [:]

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
        AWS_REGION   = "${awsRegion}"
    }

    stages {

        stage('Validate Parameters') {

            steps {

                script {

                    if (!params.VERSION?.trim()) {
                        error("VERSION cannot be empty")
                    }

                    if (!environments.containsKey(params.ENVIRONMENT)) {
                        error("Invalid ENVIRONMENT selected: ${params.ENVIRONMENT}")
                    }

                    env.AWS_TAG_ENVIRONMENT = environments[params.ENVIRONMENT]

                    echo """


==================================================
PIPELINE CONFIGURATION
======================

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}
AWS Region  : ${awsRegion}

==================================================
"""
}
}
}


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
                    echo "CURRENT WORKSPACE"
                    echo "=========================================="

                    pwd
                    ls -la

                    echo ""
                    echo "=========================================="
                    echo "VERIFY REQUIRED FILES"
                    echo "=========================================="

                    test -f docker-compose.yaml
                    test -f docker-compose-deploy.yaml
                    test -f .env

                    echo "All required files found."
                '''
            }
        }


        /*
         * KEEP YOUR EXISTING AWS DISCOVERY LOGIC HERE.
         *
         * This stage must populate:
         *
         * env.APP_PUBLIC_IP
         * env.APP_PRIVATE_IP
         * env.DB_PRIVATE_IP
         *
         * Replace only the placeholder commands below with
         * your existing working AWS discovery commands.
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

                        /*
                         * IMPORTANT:
                         * Keep your existing AWS CLI discovery logic here.
                         *
                         * Example expected final values:
                         *
                         * env.APP_PUBLIC_IP  = "x.x.x.x"
                         * env.APP_PRIVATE_IP = "10.x.x.x"
                         * env.DB_PRIVATE_IP  = "10.x.x.x"
                         */

                        echo """


==================================================
INFRASTRUCTURE DETAILS
======================

App Public IP  : ${env.APP_PUBLIC_IP}
App Private IP : ${env.APP_PRIVATE_IP}
DB Private IP  : ${env.DB_PRIVATE_IP}

==================================================
"""
}


                    if (!env.APP_PRIVATE_IP?.trim()) {
                        error("APP_PRIVATE_IP was not discovered")
                    }

                    if (!env.DB_PRIVATE_IP?.trim()) {
                        error("DB_PRIVATE_IP was not discovered")
                    }
                }
            }
        }


        /*
         * BUILD
         *
         * Uses:
         * - docker-compose.yaml
         * - .env
         */

        stage('Build Docker Images') {

            steps {

                sh """
                    set -e

                    echo "=========================================="
                    echo "BUILDING DOCKER IMAGES"
                    echo "=========================================="

                    export IMAGE_VERSION=${params.VERSION}
                    export VERSION=${params.VERSION}

                    docker compose \
                        --env-file .env \
                        -f docker-compose.yaml \
                        build

                    echo ""

                    echo "=========================================="
                    echo "BUILT DOCKER IMAGES"
                    echo "=========================================="

                    docker images | grep "${projectName}" || true
                """
            }
        }


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

                        echo "DOCKER IMAGES PUSHED SUCCESSFULLY"
                    """
                }
            }
        }


        /*
         * DEPLOY
         *
         * Jenkins workspace:
         * - docker-compose-deploy.yaml
         * - generated .env.runtime
         *
         * App EC2:
         * /home/ec2-user/erp/
         * - docker-compose-deploy.yaml
         * - .env.runtime
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
                        echo "CREATE REMOTE DEPLOYMENT DIRECTORY"
                        echo "=========================================="

                        sshpass -p "\$SSH_PASSWORD" ssh \
                            -o StrictHostKeyChecking=no \
                            "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                            "mkdir -p /home/\$SSH_USER/erp"


                        echo ""

                        echo "=========================================="
                        echo "GENERATE .env.runtime"
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

IMAGE_VERSION=${params.VERSION}
EOF


                        echo ""

                        echo "=========================================="
                        echo "COPY RUNTIME FILES TO APP EC2"
                        echo "=========================================="

                        sshpass -p "\$SSH_PASSWORD" scp \
                            -o StrictHostKeyChecking=no \
                            .env.runtime \
                            docker-compose-deploy.yaml \
                            "\$SSH_USER@${env.APP_PRIVATE_IP}:/home/\$SSH_USER/erp/"


                        echo ""

                        echo "=========================================="
                        echo "DEPLOY DOCKER CONTAINERS"
                        echo "=========================================="

                        sshpass -p "\$SSH_PASSWORD" ssh \
                            -o StrictHostKeyChecking=no \
                            "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                            "DOCKER_USERNAME='\$DOCKER_USERNAME' DOCKER_PASSWORD='\$DOCKER_PASSWORD' bash -s" <<'REMOTE_SCRIPT'


set -e

DEPLOY_DIR="/home/$USER/erp"

cd "$DEPLOY_DIR"

echo "=========================================="
echo "VERIFY REMOTE DEPLOYMENT FILES"
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
echo "CLEAN UNUSED IMAGES"
echo "=========================================="

docker image prune -af

echo ""
echo "=========================================="
echo "DEPLOYMENT COMPLETED SUCCESSFULLY"
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


    post {

        success {

            echo """


==================================================
PIPELINE COMPLETED SUCCESSFULLY
===============================

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}

Backend:
${dockerRegistry}/${projectName}-backend:${params.VERSION}

Frontend:
${dockerRegistry}/${projectName}-frontend:${params.VERSION}

==================================================
"""
}


        failure {

            echo """


==================================================
PIPELINE FAILED
===============

Project     : ${projectName}
Environment : ${params.ENVIRONMENT}
Version     : ${params.VERSION}

Check Jenkins console logs.

==================================================
"""
}


        always {

            sh """
                set +e

                echo "=========================================="
                echo "JENKINS AGENT CLEANUP"
                echo "=========================================="

                docker image prune -f || true

                docker logout || true
            """

            cleanWs(
                deleteDirs: true,
                disableDeferredWipeout: true
            )

            echo "Jenkins workspace cleanup completed."
        }
    }
}


}
