// Universal backend pipeline for all Skinex Java/Gradle services.
// Copy this file to the root of each backend repository as 'Jenkinsfile'.
// It automatically detects the service name from the git repository URL.
//
// Required Jenkins credentials:
//   - GITLAB_REGISTRY_CREDENTIALS (username/password for registry.gitlab.com)
//
// Required Jenkins environment:
//   - Docker available on the agent
//   - KUBECONFIG pointing to a valid kubeconfig (default: /var/jenkins_home/k3s.yaml)

pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'DEPLOY',
            defaultValue: true,
            description: 'Если включено — запушить образ в registry и задеплоить в Kubernetes. Если выключено — только собрать образ.'
        )
    }

    environment {
        REGISTRY = 'registry.gitlab.com/skinex-team'
        KUBECONFIG = "${env.KUBECONFIG ?: '/var/jenkins_home/k3s.yaml'}"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout & Detect Service') {
            steps {
                checkout scm
                script {
                    // repo name from GIT_URL, e.g. "users" or "bots-servise"
                    env.SERVICE = sh(
                        returnStdout: true,
                        script: "basename -s .git ${env.GIT_URL}"
                    ).trim()
                    // bots-servise repo builds image "bots-servise" but deploys as Deployment "bots"
                    env.DEPLOYMENT = env.SERVICE == 'bots-servise' ? 'bots' : env.SERVICE
                    env.IMAGE = "${env.REGISTRY}/${env.SERVICE}"
                    echo "Building service: ${env.SERVICE}, deploying as: ${env.DEPLOYMENT}"
                }
            }
        }

        stage('Build & Test') {
            steps {
                script {
                    if (env.SERVICE == 'bots-servise') {
                        sh './gradlew :app:bootJar --no-daemon -x test'
                    } else {
                        sh './gradlew bootJar --no-daemon -x test'
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    def gitSha = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    sh "docker build --build-arg GIT_SHA=${gitSha} -t ${env.IMAGE}:${gitSha} -t ${env.IMAGE}:latest ."
                }
            }
        }

        stage('Docker Push') {
            when {
                expression { params.DEPLOY }
            }
            steps {
                script {
                    def gitSha = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    withCredentials([usernamePassword(
                        credentialsId: 'GITLAB_REGISTRY_CREDENTIALS',
                        usernameVariable: 'GITLAB_REGISTRY_USER',
                        passwordVariable: 'GITLAB_REGISTRY_PASS'
                    )]) {
                        sh 'echo "$GITLAB_REGISTRY_PASS" | docker login -u "$GITLAB_REGISTRY_USER" --password-stdin registry.gitlab.com'
                    }
                    sh "docker push ${env.IMAGE}:${gitSha}"
                    sh "docker push ${env.IMAGE}:latest"
                }
            }
        }

        stage('Deploy to Kubernetes') {
            when {
                expression { params.DEPLOY }
            }
            steps {
                script {
                    def gitSha = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    sh """
                        kubectl -n skinex set image deployment/${env.DEPLOYMENT} ${env.DEPLOYMENT}=${env.IMAGE}:${gitSha}
                        kubectl -n skinex rollout status deployment/${env.DEPLOYMENT} --timeout=600s
                    """
                }
            }
        }
    }

    post {
        always {
            sh 'docker logout registry.gitlab.com || true'
        }
        failure {
            script {
                echo "Deployment failed. Consider rolling back with:"
                echo "kubectl -n skinex rollout undo deployment/${env.DEPLOYMENT}"
            }
        }
    }
}
