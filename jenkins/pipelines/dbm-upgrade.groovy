// =============================================================================
// dbm-upgrade.groovy  –  DBmaestro Upgrade Pipeline
//
// Purpose : Deploy (upgrade) a specified package to a target environment.
// Flow    : Pre-flight status → Approval email → Manual gate → Upgrade → Verify → Notify
// Linked  : On failure, optionally triggers the dbm-rollback job (Job 1 of 2)
//
// Credential Store example (recommended for production – replace DBM_USERNAME/PASSWORD params):
//   withCredentials([usernamePassword(
//       credentialsId   : 'dbmaestro-credentials',
//       usernameVariable: 'DBM_USER',
//       passwordVariable: 'DBM_PASS'
//   )]) {
//       dbmAgent([ userName: env.DBM_USER, password: env.DBM_PASS, ... ])
//   }
//
// Requires: Email Extension plugin, DBmaestro Shared Library
// =============================================================================

pipeline {
    agent { label 'windows' }

    options {
        timeout(time: 120, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
        ansiColor('xterm')
    }

    parameters {
        // ── DBmaestro Connection ──────────────────────────────────────────────
        string(name: 'PROJECT_NAME', defaultValue: 'DEMO_AS_ORACLE',       description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',   defaultValue: 'WIN-HM6PVCVCPCB:8017', description: 'DBmaestro server  host:port')

        // ── Credentials ───────────────────────────────────────────────────────
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',                 description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo', description: 'DBmaestro account password')

        // ── Target ────────────────────────────────────────────────────────────
        string(name: 'ENV_NAME',     defaultValue: 'Release Source',
               description: 'Target environment name exactly as it appears in DBmaestro (e.g. Release Source, QA, Staging, Production)')
        string(name: 'PACKAGE_NAME', defaultValue: '',
               description: 'Package to deploy. Leave blank to deploy the next available package. Run dbm-status-report to see what is available.')

        // ── Behavior ─────────────────────────────────────────────────────────
        booleanParam(name: 'BACKUP_BEHAVIOR',  defaultValue: true, description: 'Take a backup of the target DB before running the upgrade')
        booleanParam(name: 'RESTORE_BEHAVIOR', defaultValue: true, description: 'Restore from backup automatically if the upgrade fails mid-way')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com',
               description: 'Comma-separated email addresses for approval + result notifications')

        // ── Infrastructure ────────────────────────────────────────────────────
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')

        // ── Linked job ────────────────────────────────────────────────────────
        booleanParam(name: 'TRIGGER_ROLLBACK_ON_FAILURE', defaultValue: false,
                     description: 'If the upgrade fails, automatically queue the dbm-rollback job with the same parameters')
    }

    stages {

        // ── Stage 1: Pre-Flight ───────────────────────────────────────────────
        stage('Pre-Flight Status Check') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro UPGRADE  |  ${params.PROJECT_NAME}"
                    echo "  Environment : ${params.ENV_NAME}"
                    echo "  Package     : ${params.PACKAGE_NAME ?: '(select at approval gate)'}"
                    echo "════════════════════════════════════════════════════════════"

                    dbmGetStatus([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD,
                        envName    : params.ENV_NAME
                    ])

                    def pkgInfo = dbmGetPackages([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD
                    ])

                    // Store available package list for the approval gate dropdown
                    env.AVAILABLE_PACKAGES      = pkgInfo.available.join(', ') ?: '(none)'
                    env.AVAILABLE_PACKAGES_LIST = pkgInfo.available.join('\n')  ?: '(none)'
                }
            }
        }

        // ── Stage 2: Approval Gate ────────────────────────────────────────────
        // If PACKAGE_NAME was left blank, a live dropdown of available packages
        // is shown at this gate — no extra plugins required.
        stage('Approval Gate') {
            steps {
                script {
                    dbmNotify([
                        to     : params.NOTIFY_EMAIL,
                        subject: "APPROVAL REQUIRED – Upgrade → [${params.ENV_NAME}]",
                        type   : 'approval',
                        body   : """
                          <h3 style="margin-top:0;">An upgrade is awaiting your approval.</h3>
                          <table width="100%" style="border-collapse:collapse;font-size:14px;">
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package (pre-selected)</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(choose at approval gate)'}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Available packages</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.AVAILABLE_PACKAGES}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Backup before upgrade</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.BACKUP_BEHAVIOR}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Restore on failure</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.RESTORE_BEHAVIOR}</td></tr>
                          </table>
                          <br/>
                          <p style="font-size:15px;">
                            <b>Approve or abort this build:</b><br/>
                            <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                          </p>
                        """
                    ])

                    // If PACKAGE_NAME was pre-filled, confirm and proceed.
                    // If left blank, show a live dropdown of available packages.
                    if (params.PACKAGE_NAME?.trim()) {
                        input(
                            message: "Approve upgrade of [${params.PACKAGE_NAME}] to [${params.ENV_NAME}]?",
                            ok     : 'Approve & Deploy'
                        )
                        env.SELECTED_PACKAGE = params.PACKAGE_NAME
                    } else {
                        def availableList = env.AVAILABLE_PACKAGES_LIST?.split('\n').toList() ?: []
                        if (availableList.isEmpty() || availableList == ['(none)']) {
                            error "No packages available to deploy to [${params.ENV_NAME}]. Run dbm-status-report to investigate."
                        }
                        // Show dropdown of real package names from DBmaestro
                        def chosen = input(
                            message: "Select package to deploy to [${params.ENV_NAME}]:",
                            ok     : 'Approve & Deploy',
                            parameters: [
                                choice(
                                    name       : 'PACKAGE',
                                    choices    : availableList,
                                    description: 'Available packages fetched live from DBmaestro'
                                )
                            ]
                        )
                        env.SELECTED_PACKAGE = chosen
                        echo "Package selected at approval gate: ${env.SELECTED_PACKAGE}"
                    }
                }
            }
        }

        // ── Stage 3: Execute Upgrade ──────────────────────────────────────────
        stage('Execute Upgrade') {
            steps {
                script {
                    echo "Starting DBmaestro Upgrade — Package: ${env.SELECTED_PACKAGE}"
                    dbmAgent([
                        agentJar       : params.AGENT_JAR,
                        operation      : '-Upgrade',
                        projectName    : params.PROJECT_NAME,
                        server         : params.DBM_SERVER,
                        userName       : params.DBM_USERNAME,
                        password       : params.DBM_PASSWORD,
                        envName        : params.ENV_NAME,
                        packageName    : env.SELECTED_PACKAGE ?: 'True',
                        backupBehavior : params.BACKUP_BEHAVIOR,
                        restoreBehavior: params.RESTORE_BEHAVIOR
                    ])
                }
            }
        }

        // ── Stage 4: Post-Deploy Verification ─────────────────────────────────
        stage('Post-Deploy Verification') {
            steps {
                script {
                    def statusAfter = dbmGetStatus([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD,
                        envName    : params.ENV_NAME
                    ])
                    echo "Verification complete. Packages in environment: ${statusAfter.size()}"
                }
            }
        }
    }

    post {
        success {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "SUCCESS – Upgrade [${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: 'next'}] → [${params.ENV_NAME}]",
                    type   : 'success',
                    body   : """
                      <h3 style="margin-top:0;color:#27ae60;">Upgrade completed successfully.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package deployed</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Duration</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${currentBuild.durationString}</td></tr>
                      </table>
                    """
                ])
            }
        }
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Upgrade [${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: 'next'}] → [${params.ENV_NAME}]",
                    type   : 'failure',
                    body   : """
                      <h3 style="margin-top:0;color:#c0392b;">Upgrade FAILED. No automatic rollback was performed.</h3>
                      <p>Review the console output and determine corrective action before retrying.</p>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Rollback triggered</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.TRIGGER_ROLLBACK_ON_FAILURE}</td></tr>
                      </table>
                      <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                    """
                ])

                if (params.TRIGGER_ROLLBACK_ON_FAILURE) {
                    echo "Queuing dbm-rollback job (TRIGGER_ROLLBACK_ON_FAILURE=true)..."
                    build(
                        job : 'dbm-rollback',
                        wait: false,
                        parameters: [
                            string      (name: 'PROJECT_NAME',    value: params.PROJECT_NAME),
                            string      (name: 'DBM_SERVER',      value: params.DBM_SERVER),
                            string      (name: 'DBM_USERNAME',    value: params.DBM_USERNAME),
                            password    (name: 'DBM_PASSWORD',    value: params.DBM_PASSWORD),
                            string      (name: 'ENV_NAME',        value: params.ENV_NAME),
                            string      (name: 'PACKAGE_NAME',    value: params.PACKAGE_NAME),
                            booleanParam(name: 'BACKUP_BEHAVIOR', value: params.BACKUP_BEHAVIOR),
                            booleanParam(name: 'RESTORE_BEHAVIOR',value: params.RESTORE_BEHAVIOR),
                            string      (name: 'NOTIFY_EMAIL',    value: params.NOTIFY_EMAIL),
                            string      (name: 'AGENT_JAR',       value: params.AGENT_JAR)
                        ]
                    )
                }
            }
        }
        aborted {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "ABORTED – Upgrade [${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: 'next'}] → [${params.ENV_NAME}]",
                    type   : 'info',
                    body   : '<p>The upgrade was aborted at the approval gate or manually cancelled during execution. No changes were made.</p>'
                ])
            }
        }
    }
}
