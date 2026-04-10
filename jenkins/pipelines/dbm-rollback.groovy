// =============================================================================
// dbm-rollback.groovy  –  DBmaestro Rollback Pipeline
//
// Purpose : Roll back the last deployed package on a target environment.
// Flow    : Pre-flight status → Approval email → Manual gate → Rollback → Verify → Notify
// Linked  : On success, optionally re-triggers the dbm-upgrade job (Job 2 of 2)
//           Can also be triggered automatically by dbm-upgrade on failure.
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
               description: 'Environment to roll back (e.g. Release Source, QA, Staging, Production)')
        string(name: 'PACKAGE_NAME', defaultValue: '',
               description: 'Package to roll back to. Leave blank to roll back the most recently deployed package.')

        // ── Behavior ─────────────────────────────────────────────────────────
        booleanParam(name: 'BACKUP_BEHAVIOR',  defaultValue: true, description: 'Take a backup before executing the rollback')
        booleanParam(name: 'RESTORE_BEHAVIOR', defaultValue: true, description: 'Restore from backup if the rollback itself fails')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com',
               description: 'Comma-separated email addresses for approval + result notifications')

        // ── Infrastructure ────────────────────────────────────────────────────
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')

        // ── Linked job ────────────────────────────────────────────────────────
        booleanParam(name: 'TRIGGER_UPGRADE_AFTER_ROLLBACK', defaultValue: false,
                     description: 'After a successful rollback, queue the dbm-upgrade job with the same parameters for re-deployment')
    }

    stages {

        // ── Stage 1: Pre-Flight ───────────────────────────────────────────────
        stage('Pre-Flight Status Check') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro ROLLBACK  |  ${params.PROJECT_NAME}"
                    echo "  Environment : ${params.ENV_NAME}"
                    echo "  Package     : ${params.PACKAGE_NAME ?: '(most recent)'}"
                    echo "════════════════════════════════════════════════════════════"

                    def statusBefore = dbmGetStatus([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD,
                        envName    : params.ENV_NAME
                    ])

                    def currentPkg = statusBefore.find { pkg ->
                        pkg.EnvDeployed && !pkg.EnvDeployed.toString().trim().isEmpty()
                    }
                    env.CURRENT_DEPLOYED = currentPkg ? (currentPkg.VersionName ?: currentPkg.Name) : '(unknown)'
                    echo "Currently deployed: ${env.CURRENT_DEPLOYED}"
                }
            }
        }

        // ── Stage 2: Approval Gate ────────────────────────────────────────────
        stage('Approval Gate') {
            steps {
                script {
                    dbmNotify([
                        to     : params.NOTIFY_EMAIL,
                        subject: "APPROVAL REQUIRED – Rollback [${params.ENV_NAME}] from [${env.CURRENT_DEPLOYED}]",
                        type   : 'approval',
                        body   : """
                          <h3 style="margin-top:0;">A rollback is awaiting your approval.</h3>
                          <p style="color:#c0392b;font-weight:bold;">
                            WARNING: This will revert database changes on [${params.ENV_NAME}].
                          </p>
                          <table width="100%" style="border-collapse:collapse;font-size:14px;">
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Currently deployed</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.CURRENT_DEPLOYED}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Roll back to</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(previous package)'}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Backup before rollback</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.BACKUP_BEHAVIOR}</td></tr>
                          </table>
                          <br/>
                          <p style="font-size:15px;">
                            <b>Approve or abort this build:</b><br/>
                            <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                          </p>
                        """
                    ])

                    input(
                        message: "Approve rollback on [${params.ENV_NAME}]? Currently: ${env.CURRENT_DEPLOYED}",
                        ok     : 'Approve & Rollback'
                    )
                }
            }
        }

        // ── Stage 3: Execute Rollback ─────────────────────────────────────────
        stage('Execute Rollback') {
            steps {
                script {
                    echo "Starting DBmaestro Rollback..."
                    dbmAgent([
                        agentJar       : params.AGENT_JAR,
                        operation      : '-Rollback',
                        projectName    : params.PROJECT_NAME,
                        server         : params.DBM_SERVER,
                        userName       : params.DBM_USERNAME,
                        password       : params.DBM_PASSWORD,
                        envName        : params.ENV_NAME,
                        packageName    : params.PACKAGE_NAME ?: 'True',
                        backupBehavior : params.BACKUP_BEHAVIOR,
                        restoreBehavior: params.RESTORE_BEHAVIOR
                    ])
                }
            }
        }

        // ── Stage 4: Post-Rollback Verification ───────────────────────────────
        stage('Post-Rollback Verification') {
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
                    echo "Post-rollback state captured. Packages in environment: ${statusAfter.size()}"
                }
            }
        }
    }

    post {
        success {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "SUCCESS – Rollback completed on [${params.ENV_NAME}]",
                    type   : 'success',
                    body   : """
                      <h3 style="margin-top:0;color:#27ae60;">Rollback completed successfully.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Was deployed</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.CURRENT_DEPLOYED}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Duration</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${currentBuild.durationString}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Re-upgrade queued</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.TRIGGER_UPGRADE_AFTER_ROLLBACK}</td></tr>
                      </table>
                    """
                ])

                if (params.TRIGGER_UPGRADE_AFTER_ROLLBACK) {
                    echo "Queuing dbm-upgrade job (TRIGGER_UPGRADE_AFTER_ROLLBACK=true)..."
                    build(
                        job : 'dbm-upgrade',
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
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Rollback on [${params.ENV_NAME}]",
                    type   : 'failure',
                    body   : """
                      <h3 style="margin-top:0;color:#c0392b;">Rollback FAILED. Manual intervention required.</h3>
                      <p>The environment [${params.ENV_NAME}] may be in an inconsistent state.
                         Please check the DBmaestro UI and console log immediately.</p>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Was deployed</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.CURRENT_DEPLOYED}</td></tr>
                      </table>
                      <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                    """
                ])
            }
        }
        aborted {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "ABORTED – Rollback on [${params.ENV_NAME}]",
                    type   : 'info',
                    body   : '<p>The rollback was aborted at the approval gate or manually cancelled. No changes were made.</p>'
                ])
            }
        }
    }
}
