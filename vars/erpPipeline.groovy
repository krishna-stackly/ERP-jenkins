def call(Map config = [:]) {

    /*
     * ========================================================
     * CONFIGURATION
     * ========================================================
     */

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

    /*
     * ========================================================
     * ENVIRONMENT OPTIONS
     *
     * Jenkins Declarative parameters cannot reliably evaluate
     * environments.keySet().join() directly inside parameters.
     *
     * Convert the configured environments before pipeline().
     * ========================================================
     */

    def environmentChoices = environments.keySet().join('\n')


    pipeline {

        agent any


        options {

            timestamps()

            skipDefaultCheckout(true)

        }


        parameters {

            choice(
                name: 'ENVIRONMENT',
                choices: environmentChoices,
                description: 'Select deployment environment'
            )

            string(
                name: 'VERSION',
                defaultValue: '1.0.0',
                description: 'Docker image semantic version (example: 1.0.0)'
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


                        /*
                         * Semantic version validation.
                         *
                         * Valid examples:
                         * 1.0.0
                         * 2.5.3
                         * 1.0.0-dev
                         */

//                         if (!(params.VERSION ==~ /^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)?$/)) {

//                             error("""
// Invalid VERSION: ${params.VERSION}

// Use semantic versioning.

// Examples:

// 1.0.0
// 1.2.0
// 2.0.5
// 1.0.0-dev
// """)

                        }


                        if (!environments.containsKey(params.ENVIRONMENT)) {

                            error("""
Invalid ENVIRONMENT selected: ${params.ENVIRONMENT}

Available environments:

${environmentChoices}
""")

                        }


                        /*
                         * Convert Jenkins environment into
                         * actual AWS tag environment value.
                         */

                        env.AWS_TAG_ENVIRONMENT =
                            environments[params.ENVIRONMENT]


                        echo """
==================================================
PIPELINE CONFIGURATION
==================================================

Project             : ${projectName}

Jenkins Environment : ${params.ENVIRONMENT}

AWS Tag Environment : ${env.AWS_TAG_ENVIRONMENT}

Version             : ${params.VERSION}

AWS Region          : ${awsRegion}

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

                    cleanWs(
                        deleteDirs: true,
                        disableDeferredWipeout: true
                    )


                    git(

                        branch: 'main',

                        credentialsId: gitCredentials,

                        url: applicationRepo

                    )


                    sh """
                        set -e

                        echo "=========================================="
                        echo "APPLICATION REPOSITORY"
                        echo "=========================================="

                        pwd

                        echo ""

                        ls -la

                        echo ""

                        echo "=========================================="
                        echo "VERIFY REQUIRED FILES"
                        echo "=========================================="

                        test -f docker-compose.yaml

                        test -f docker-compose-deploy.yaml

                        test -f .env

                        echo ""

                        echo "Application repository cloned successfully."

                    """

                }

            }


            /*
             * ========================================================
             * DISCOVER INFRASTRUCTURE
             * ========================================================
             */

            stage('Discover Infrastructure') {

                steps {

                    withCredentials([

                        usernamePassword(

                            credentialsId: awsCredentials,

                            usernameVariable: 'AWS_ACCESS_KEY_ID',

                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'

                        )

                    ]) {

                        script {

                            sh """
                                set -e

                                export AWS_DEFAULT_REGION=${awsRegion}

                                echo "=========================================="
                                echo "VERIFYING AWS AUTHENTICATION"
                                echo "=========================================="

                                aws sts get-caller-identity

                                echo ""

                                echo "AWS Region          : ${awsRegion}"
                                echo "Project             : ${projectName}"
                                echo "Jenkins Environment : ${params.ENVIRONMENT}"
                                echo "AWS Tag Environment : ${env.AWS_TAG_ENVIRONMENT}"
                                echo "Created By          : ${pocName}"

                                echo "=========================================="
                            """


                            /*
                             * APPLICATION EC2
                             */

                            env.APP_PUBLIC_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${env.AWS_TAG_ENVIRONMENT}" \
                                            "Name=tag:component,Values=app" \
                                            "Name=tag:Created_by,Values=${pocName}" \
                                            "Name=tag:State,Values=non-persistent" \
                                            "Name=instance-state-name,Values=running" \
                                        --query 'Reservations[].Instances[].PublicIpAddress' \
                                        --output text
                                """,

                                returnStdout: true

                            ).trim()


                            env.APP_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${env.AWS_TAG_ENVIRONMENT}" \
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
                             * SHARED DATABASE EC2
                             *
                             * Database is private subnet only.
                             */

                            env.DB_PRIVATE_IP = sh(

                                script: """
                                    aws ec2 describe-instances \
                                        --region ${awsRegion} \
                                        --filters \
                                            "Name=tag:Project,Values=${projectName}" \
                                            "Name=tag:Environment,Values=${env.AWS_TAG_ENVIRONMENT}" \
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
                             * VALIDATE RESULTS
                             */

                            if (!env.APP_PUBLIC_IP ||
                                env.APP_PUBLIC_IP == 'None' ||
                                env.APP_PUBLIC_IP == 'null') {

                                error("""
Application EC2 Public IP was not found.

Expected tags:

Project     = ${projectName}
Environment = ${env.AWS_TAG_ENVIRONMENT}
component   = app
Created_by  = ${pocName}
State       = non-persistent
""")

                            }


                            if (!env.APP_PRIVATE_IP ||
                                env.APP_PRIVATE_IP == 'None' ||
                                env.APP_PRIVATE_IP == 'null') {

                                error("""
Application EC2 Private IP was not found.
""")

                            }


                            if (!env.DB_PRIVATE_IP ||
                                env.DB_PRIVATE_IP == 'None' ||
                                env.DB_PRIVATE_IP == 'null') {

                                error("""
Shared Database Private IP was not found.

Expected tags:

Project     = ${projectName}
Environment = ${env.AWS_TAG_ENVIRONMENT}
component   = database
Created_by  = ${pocName}
Lifecycle   = Persistent
""")

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
                        echo "BUILDING DOCKER IMAGES"
                        echo "=========================================="

                        export VERSION=${params.VERSION}

                        docker compose \
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

                            echo "\$DOCKER_PASSWORD" | \
                                docker login \
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

                            echo "Docker images pushed successfully."

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

                        sh """
                            set -e

                            echo "=========================================="
                            echo "DEPLOYMENT CONFIGURATION"
                            echo "=========================================="

                            echo "App Public IP  : ${env.APP_PUBLIC_IP}"

                            echo "App Private IP : ${env.APP_PRIVATE_IP}"

                            echo "DB Private IP  : ${env.DB_PRIVATE_IP}"

                            echo "Version        : ${params.VERSION}"


                            echo ""

                            echo "=========================================="
                            echo "VERIFY SSH CONNECTIVITY"
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
                                "mkdir -p /home/\$SSH_USER/${projectName}"


                            echo ""

                            echo "=========================================="
                            echo "GENERATE RUNTIME ENVIRONMENT"
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

MEDIA_URL=/media/

PORT=8000

VERSION=${params.VERSION}
EOF


                            echo ""

                            echo "=========================================="
                            echo "COPY DEPLOYMENT FILES"
                            echo "=========================================="

                            sshpass -p "\$SSH_PASSWORD" scp \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                .env.runtime \
                                docker-compose-deploy.yaml \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}:/home/\$SSH_USER/${projectName}/"


                            echo ""

                            echo "Deployment files copied successfully."


                            echo ""

                            echo "=========================================="
                            echo "DEPLOY REMOTELY"
                            echo "=========================================="


                            sshpass -p "\$SSH_PASSWORD" ssh \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                "\$SSH_USER@${env.APP_PRIVATE_IP}" \
                                "DOCKER_USERNAME='\$DOCKER_USERNAME' DOCKER_PASSWORD='\$DOCKER_PASSWORD' PROJECT_NAME='${projectName}' bash -s" <<'REMOTE_SCRIPT'

set -e

DEPLOY_DIR="/home/$USER/$PROJECT_NAME"

cd "$DEPLOY_DIR"


echo "=========================================="
echo "DEPLOYMENT DIRECTORY"
echo "=========================================="

pwd

ls -la


echo ""

echo "=========================================="
echo "VERIFY DEPLOYMENT FILES"
echo "=========================================="

test -f .env.runtime

test -f docker-compose-deploy.yaml


echo ""

echo "=========================================="
echo "DOCKER LOGIN"
echo "=========================================="

echo "$DOCKER_PASSWORD" | \
    docker login \
    -u "$DOCKER_USERNAME" \
    --password-stdin


echo ""

echo "=========================================="
echo "PULL APPLICATION IMAGES"
echo "=========================================="

docker compose \
    --env-file .env.runtime \
    -f docker-compose-deploy.yaml \
    pull


echo ""

echo "=========================================="
echo "START APPLICATION"
echo "=========================================="

docker compose \
    --env-file .env.runtime \
    -f docker-compose-deploy.yaml \
    up -d


echo ""

echo "=========================================="
echo "APPLICATION STATUS"
echo "=========================================="

docker compose \
    --env-file .env.runtime \
    -f docker-compose-deploy.yaml \
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

Images:

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

Project     : ${projectName}

Environment : ${params.ENVIRONMENT}

Version     : ${params.VERSION}

Check Jenkins stage logs.

==================================================
"""

            }


            always {

                sh """
                    set +e

                    echo "=========================================="
                    echo "JENKINS AGENT DOCKER CLEANUP"
                    echo "=========================================="

                    docker logout || true

                    docker image rm \
                        ${dockerRegistry}/${projectName}-backend:${params.VERSION} \
                        2>/dev/null || true

                    docker image rm \
                        ${dockerRegistry}/${projectName}-frontend:${params.VERSION} \
                        2>/dev/null || true

                    docker image prune -af || true

                    docker builder prune -af || true

                    echo "Docker cleanup completed."

                """


                cleanWs(
                    deleteDirs: true,
                    disableDeferredWipeout: true
                )


                echo "Jenkins workspace cleanup completed."

            }

        }

    }

  
