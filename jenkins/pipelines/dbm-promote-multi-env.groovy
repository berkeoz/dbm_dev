// =============================================================================
// dbm-promote-multi-env.groovy  –  DBmaestro Multi-Environment Promotion Pipeline
//
// Purpose : Promote a single package through an ordered chain of environments
//           (e.g. Release Source → QA → Staging → Production).
//           Each environment gets its own approval gate and status check.
//           The chain stops immediately if any upgrade fails.
//
// Flow (per environment in the chain):
//   Pre-check → Approval email → Manual gate → Upgrade → Post-verify
//   On failure: alert, mark FAILED, stop chain (no auto-rollback)
//
// Requires: Email Extension plugin, DBmaestro Shared Library
// =============================================================================

pipeline {
    agent { label 'windows' }

    options {
        timeout(time: 480, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
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

        // ── Promotion chain ───────────────────────────────────────────────────
        string(name: 'PACKAGE_NAME', defaultValue: '',
               description: 'Package to promote. Leave blank to promote the next available package at each environment.')
        string(name: 'ENVIRONMENTS', defaultValue: 'Release Source,QA',
               description: 'Ordered comma-separated list of environments to promote through.\nExamples: Release Source,QA   |   Release Source,QA,Staging,Production')

        // ── Behavior ─────────────────────────────────────────────────────────
        booleanParam(name: 'BACKUP_BEHAVIOR',      defaultValue: true,  description: 'Take a backup before each upgrade')
        booleanParam(name: 'RESTORE_BEHAVIOR',     defaultValue: true,  description: 'Restore from backup if an upgrade fails mid-way')
        booleanParam(name: 'STOP_ON_FIRST_FAILURE',defaultValue: true,  description: 'Stop the entire chain if any environment upgrade fails')
        booleanParam(name: 'APPROVAL_GATE_PER_ENV',defaultValue: true,
                     description: 'Require manual approval before each environment. Set false for fully automated chains (use with caution).')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com',
               description: 'Comma-separated email addresses for all approval requests and notifications')

        // ── Infrastructure ────────────────────────────────────────────────────
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')
    }

    stages {

        // ── Stage 1: Validate Parameters ──────────────────────────────────────
        stage('Validate Parameters') {
            steps {
                script {
                    def envChain = params.ENVIRONMENTS
                        .split(',')
                        .collect { it.trim() }
                        .findAll { it }

                    if (envChain.isEmpty()) {
                        error "ENVIRONMENTS parameter is empty. Provide at least one environment name."
                    }

                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro MULTI-ENV PROMOTION  |  ${params.PROJECT_NAME}"
                    echo "  Package     : ${params.PACKAGE_NAME ?: '(next available)'}"
                    echo "  Chain       : ${envChain.join(' → ')}"
                    echo "  Approvals   : ${params.APPROVAL_GATE_PER_ENV ? 'per environment' : 'DISABLED'}"
                    echo "  Stop on fail: ${params.STOP_ON_FIRST_FAILURE}"
                    echo "════════════════════════════════════════════════════════════"

                    env.ENV_CHAIN_JSON = groovy.json.JsonOutput.toJson(envChain)
                }
            }
        }

        // ── Stage 2: Promote Through Environments ─────────────────────────────
        stage('Promote Through Environments') {
            steps {
                script {
                    def envChain      = new groovy.json.JsonSlurper().parseText(env.ENV_CHAIN_JSON) as List<String>
                    def failedEnvs    = []
                    def succeededEnvs = []

                    envChain.eachWithIndex { envName, idx ->

                        echo ""
                        echo "══════════════════════════════════════════════════════════"
                        echo "  [${idx + 1}/${envChain.size()}]  Environment: ${envName}"
                        echo "══════════════════════════════════════════════════════════"

                        // ── Pre-check ──────────────────────────────────────────
                        def statusBefore = []
                        try {
                            statusBefore = dbmGetStatus([
                                agentJar   : params.AGENT_JAR,
                                projectName: params.PROJECT_NAME,
                                server     : params.DBM_SERVER,
                                userName   : params.DBM_USERNAME,
                                password   : params.DBM_PASSWORD,
                                envName    : envName
                            ])
                        } catch (Exception ex) {
                            echo "WARNING: Could not retrieve pre-status for [${envName}]: ${ex.message}"
                        }

                        def currentPkg = statusBefore.find { it.EnvDeployed && !it.EnvDeployed.toString().trim().isEmpty() }
                        def currentVersion = currentPkg ? (currentPkg.VersionName ?: currentPkg.Name) : '(nothing deployed)'

                        // ── Approval gate ──────────────────────────────────────
                        if (params.APPROVAL_GATE_PER_ENV) {
                            dbmNotify([
                                to     : params.NOTIFY_EMAIL,
                                subject: "APPROVAL [${idx + 1}/${envChain.size()}] – Deploy [${params.PACKAGE_NAME ?: 'next'}] → [${envName}]",
                                type   : 'approval',
                                body   : """
                                  <h3 style="margin-top:0;">Promotion step awaiting approval.</h3>
                                  <table width="100%" style="border-collapse:collapse;font-size:14px;">
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Step</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${idx + 1} of ${envChain.size()}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Project</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Target environment</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;"><b>${envName}</b></td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Currently deployed</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${currentVersion}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package to deploy</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Completed so far</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${succeededEnvs.join(' → ') ?: '—'}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Remaining after this</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${envChain.drop(idx + 1).join(' → ') ?: '(this is the last)'}</td></tr>
                                  </table>
                                  <br/>
                                  <p style="font-size:15px;">
                                    <b>Approve or abort at:</b><br/>
                                    <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                                  </p>
                                """
                            ])

                            input(
                                message: "Approve deploy [${params.PACKAGE_NAME ?: 'next'}] → [${envName}]?",
                                ok     : "Deploy to ${envName}"
                            )
                        }

                        // ── Execute Upgrade ────────────────────────────────────
                        try {
                            dbmAgent([
                                agentJar       : params.AGENT_JAR,
                                operation      : '-Upgrade',
                                projectName    : params.PROJECT_NAME,
                                server         : params.DBM_SERVER,
                                userName       : params.DBM_USERNAME,
                                password       : params.DBM_PASSWORD,
                                envName        : envName,
                                packageName    : params.PACKAGE_NAME ?: 'True',
                                backupBehavior : params.BACKUP_BEHAVIOR,
                                restoreBehavior: params.RESTORE_BEHAVIOR
                            ])

                            // Post-check
                            try {
                                dbmGetStatus([
                                    agentJar   : params.AGENT_JAR,
                                    projectName: params.PROJECT_NAME,
                                    server     : params.DBM_SERVER,
                                    userName   : params.DBM_USERNAME,
                                    password   : params.DBM_PASSWORD,
                                    envName    : envName
                                ])
                            } catch (Exception postEx) {
                                echo "WARNING: Post-check failed for [${envName}]: ${postEx.message}"
                            }

                            succeededEnvs << envName
                            echo "✓ [${envName}] upgrade successful."

                            dbmNotify([
                                to     : params.NOTIFY_EMAIL,
                                subject: "SUCCESS [${idx + 1}/${envChain.size()}] – [${envName}] deployed",
                                type   : 'success',
                                body   : """
                                  <h3 style="margin-top:0;color:#27ae60;">[${envName}] upgrade completed.</h3>
                                  <p>Package: <b>${params.PACKAGE_NAME ?: '(next available)'}</b></p>
                                  <p>Completed: ${succeededEnvs.join(' → ')}</p>
                                  <p>Remaining: ${envChain.drop(idx + 1).join(' → ') ?: '(all environments promoted)'}</p>
                                """
                            ])

                        } catch (Exception upgradeEx) {
                            failedEnvs << envName
                            echo "✗ [${envName}] upgrade FAILED: ${upgradeEx.message}"

                            dbmNotify([
                                to     : params.NOTIFY_EMAIL,
                                subject: "FAILED [${idx + 1}/${envChain.size()}] – [${envName}] failed – chain stopping",
                                type   : 'failure',
                                body   : """
                                  <h3 style="margin-top:0;color:#c0392b;">[${envName}] upgrade FAILED. Promotion chain stopped.</h3>
                                  <table width="100%" style="border-collapse:collapse;font-size:14px;">
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:35%;"><b>Failed environment</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${envName}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Successfully promoted</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${succeededEnvs.join(' → ') ?: '—'}</td></tr>
                                    <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Skipped</b></td>
                                        <td style="padding:7px 10px;border:1px solid #e0e0e0;">${envChain.drop(idx + 1).join(', ') ?: '—'}</td></tr>
                                  </table>
                                  <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                                """
                            ])

                            if (params.STOP_ON_FIRST_FAILURE) {
                                error "Promotion stopped: upgrade failed on [${envName}]."
                            }
                        }
                    }

                    env.SUCCEEDED_ENVS = succeededEnvs.join(', ')
                    env.FAILED_ENVS    = failedEnvs.join(', ')

                    echo ""
                    echo "════════════════════════════════════════════════════════════"
                    echo "  PROMOTION SUMMARY"
                    echo "  Succeeded : ${succeededEnvs.join(' → ') ?: '(none)'}"
                    echo "  Failed    : ${failedEnvs.join(', ')    ?: '(none)'}"
                    echo "════════════════════════════════════════════════════════════"

                    if (!failedEnvs.isEmpty()) {
                        error "Promotion completed with failures on: ${failedEnvs.join(', ')}"
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "SUCCESS – Full promotion [${params.PROJECT_NAME}] complete",
                    type   : 'success',
                    body   : """
                      <h3 style="margin-top:0;color:#27ae60;">All environments promoted successfully.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Promoted to</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.SUCCEEDED_ENVS ?: params.ENVIRONMENTS}</td></tr>
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
                    subject: "FAILED – Promotion [${params.PROJECT_NAME}] – chain stopped",
                    type   : 'failure',
                    body   : """
                      <h3 style="margin-top:0;color:#c0392b;">Promotion chain failed or was aborted.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Successfully promoted</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.SUCCEEDED_ENVS ?: '—'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Failed</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.FAILED_ENVS ?: '—'}</td></tr>
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
                    subject: "ABORTED – Promotion [${params.PROJECT_NAME}]",
                    type   : 'info',
                    body   : """
                      <p>The multi-environment promotion was aborted (approval gate rejected or manually cancelled).</p>
                      <p>Successfully promoted before abort: <b>${env.SUCCEEDED_ENVS ?: '(none)'}</b></p>
                    """
                ])
            }
        }
    }
}
