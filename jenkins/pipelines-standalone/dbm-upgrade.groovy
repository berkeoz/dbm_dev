// =============================================================================
// dbm-upgrade.groovy – Self-contained, no shared library required
// Paste directly into Jenkins job → Pipeline → "Pipeline script"
// =============================================================================

// ── Inlined: dbmAgent ────────────────────────────────────────────────────────
def dbmAgent(Map cfg) {
    def authType = cfg.get('authType', 'DBmaestroAccount')
    def isLinux  = cfg.get('isLinux', false)
    List args = []
    args << "java" << "-jar" << "\"${cfg.agentJar}\""
    args << cfg.operation
    args << "-ProjectName" << "\"${cfg.projectName}\""
    args << "-Server"      << "\"${cfg.server}\""
    args << "-AuthType"    << authType
    args << "-UserName"    << "\"${cfg.userName}\""
    args << "-Password"    << "\"${cfg.password}\""
    if (cfg.envName)                 { args << "-EnvName"         << "\"${cfg.envName}\""   }
    if (cfg.packageName)             { args << "-PackageName"     << "\"${cfg.packageName}\"" }
    if (cfg.backupBehavior  != null) { args << "-BackupBehavior"  << (cfg.backupBehavior  ? 'True' : 'False') }
    if (cfg.restoreBehavior != null) { args << "-RestoreBehavior" << (cfg.restoreBehavior ? 'True' : 'False') }
    if (cfg.filePath)                { args << "-FilePath"        << "\"${cfg.filePath}\""  }
    if (cfg.extraArgs)               { args.addAll(cfg.extraArgs) }
    def cmd       = args.join(' ')
    def maskedCmd = cmd.replace(cfg.password.toString(), '********')
    echo "DBmaestro Agent ► ${maskedCmd}"
    def exitCode = isLinux ? sh(script: cmd, returnStatus: true) : bat(script: cmd, returnStatus: true)
    if (exitCode != 0) { error "DBmaestroAgent.jar exited ${exitCode} for operation: ${cfg.operation}" }
}

// ── Inlined: dbmGetPackages ──────────────────────────────────────────────────
def dbmGetPackages(Map cfg) {
    def isLinux = cfg.get('isLinux', false)
    def tmpFile = "${env.WORKSPACE}${isLinux ? '/' : '\\'}dbm_packages_${System.currentTimeMillis()}.json"
    try {
        dbmAgent([agentJar: cfg.agentJar, operation: '-GetPackages', projectName: cfg.projectName,
                  server: cfg.server, userName: cfg.userName, password: cfg.password,
                  filePath: tmpFile, isLinux: isLinux])
        def allPkgs = readJSON(text: readFile(file: tmpFile).trim())
        def available = allPkgs.findAll { p ->
            p.State == 0 && p.IsEnabled == true && p.IsTemporary == false && p.IsAdhocPackage == false
        }
        def availableNames = available.collect { it.Name as String }
        availableNames.sort()
        def allNames = allPkgs.collect { it.Name as String }
        echo "┌─ Packages for [${cfg.projectName}] ──────────────────────────────────"
        echo "│  Total: ${allNames.size()}   Available: ${availableNames.size()}"
        availableNames.each { name -> echo "│    ✓ ${name}" }
        if (availableNames.isEmpty()) { echo "│    (none)" }
        echo "└──────────────────────────────────────────────────────────────────"
        return [available: availableNames, all: allNames]
    } catch (Exception ex) {
        echo "WARNING: Could not list packages for [${cfg.projectName}]: ${ex.message}"
        return [available: [], all: []]
    } finally {
        isLinux ? sh("rm -f \"${tmpFile}\" 2>/dev/null || true") : bat("if exist \"${tmpFile}\" del /f /q \"${tmpFile}\"")
    }
}

// ── Inlined: dbmGetStatus ────────────────────────────────────────────────────
def dbmGetStatus(Map cfg) {
    def isLinux = cfg.get('isLinux', false)
    def safeEnv = cfg.envName.replaceAll('[^a-zA-Z0-9_-]', '_')
    def tmpFile = "${env.WORKSPACE}${isLinux ? '/' : '\\'}dbm_status_${safeEnv}_${System.currentTimeMillis()}.json"
    try {
        dbmAgent([agentJar: cfg.agentJar, operation: '-GetEnvPackages', projectName: cfg.projectName,
                  server: cfg.server, userName: cfg.userName, password: cfg.password,
                  envName: cfg.envName, filePath: tmpFile, isLinux: isLinux])
        def data = readJSON(text: readFile(file: tmpFile).trim())
        echo "┌─ Status: [${cfg.envName}] ─────────────────────────────────────────"
        data.each { pkg ->
            echo "│  ${((pkg.VersionName ?: pkg.Name) as String).padRight(20)}  EnvDeployed: ${(pkg.EnvDeployed ?: '—').toString().padRight(25)}  RSDeployed: ${pkg.RSDeployed ?: '—'}"
        }
        echo "└──────────────────────────────────────────────────────────────────"
        return data
    } catch (Exception ex) {
        echo "WARNING: Could not retrieve status for [${cfg.envName}]: ${ex.message}"
        return []
    } finally {
        isLinux ? sh("rm -f \"${tmpFile}\" 2>/dev/null || true") : bat("if exist \"${tmpFile}\" del /f /q \"${tmpFile}\"")
    }
}

// ── Inlined: dbmNotify ───────────────────────────────────────────────────────
def dbmNotify(Map cfg) {
    def colors = [approval: '#e67e22', success: '#27ae60', failure: '#c0392b', info: '#2980b9']
    def color  = colors.get(cfg.type ?: 'info', '#2980b9')
    emailext(to: cfg.to, subject: cfg.subject, mimeType: 'text/html', body: """
      <div style="font-family:Arial,sans-serif;max-width:700px;margin:0 auto;">
        <div style="background:${color};color:#fff;padding:18px 24px;border-radius:4px 4px 0 0;">
          <h2 style="margin:0;font-size:20px;">[ACTION REQUIRED] DBmaestro Pipeline</h2>
        </div>
        <div style="border:1px solid #ddd;border-top:none;padding:20px 24px;background:#fff;">
          <table width="100%" style="border-collapse:collapse;font-size:14px;margin-bottom:16px;">
            <tr><td style="padding:6px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Job</b></td>
                <td style="padding:6px 10px;border:1px solid #e0e0e0;">${env.JOB_NAME}</td></tr>
            <tr><td style="padding:6px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Build #</b></td>
                <td style="padding:6px 10px;border:1px solid #e0e0e0;">${env.BUILD_NUMBER}</td></tr>
            <tr><td style="padding:6px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Triggered by</b></td>
                <td style="padding:6px 10px;border:1px solid #e0e0e0;">${currentBuild.getBuildCauses()[0]?.shortDescription ?: 'unknown'}</td></tr>
            <tr><td style="padding:6px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Console</b></td>
                <td style="padding:6px 10px;border:1px solid #e0e0e0;"><a href="${env.BUILD_URL}console">View full log</a></td></tr>
          </table>
          ${cfg.body}
        </div>
        <div style="padding:10px 24px;font-size:11px;color:#aaa;text-align:center;">
          Sent by Jenkins DBmaestro Pipeline &mdash; do not reply
        </div>
      </div>
    """)
}

// ── Inlined: jiraComment ─────────────────────────────────────────────────────
def jiraComment(String issueKey, String body) {
    if (!issueKey?.trim()) { return }
    try {
        jiraAddComment idOrKey: issueKey, comment: body
    } catch (Exception ex) {
        echo "WARNING: Could not post Jira comment to ${issueKey}: ${ex.message}"
    }
}

// =============================================================================
// Pipeline
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
        string(name: 'PROJECT_NAME', defaultValue: 'DEMO_AS_ORACLE',       description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',   defaultValue: 'WIN-HM6PVCVCPCB:8017', description: 'DBmaestro server host:port')
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',                    description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo',    description: 'DBmaestro account password')
        choice(name: 'ENV_NAME', choices: ['Release Source', 'QA_Env_1', 'Staging', 'Production'],
               description: 'Target environment name exactly as it appears in DBmaestro')
        string(name: 'PACKAGE_NAME', defaultValue: '', description: 'Package to deploy. Leave blank to choose at approval gate.')
        booleanParam(name: 'BACKUP_BEHAVIOR',  defaultValue: true, description: 'Take a backup before running the upgrade')
        booleanParam(name: 'RESTORE_BEHAVIOR', defaultValue: true, description: 'Restore from backup if upgrade fails')
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com', description: 'Notification recipients')
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar')
        booleanParam(name: 'TRIGGER_ROLLBACK_ON_FAILURE', defaultValue: false,
                     description: 'Queue dbm-rollback job automatically if upgrade fails')
        string(name: 'JIRA_ISSUE_KEY', defaultValue: '', description: 'Jira issue key to update on completion (e.g. DBM-42). Leave blank if not triggered from Jira.')
    }

    stages {

        stage('Pre-Flight Status Check') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro UPGRADE  |  ${params.PROJECT_NAME}"
                    echo "  Environment : ${params.ENV_NAME}"
                    echo "  Package     : ${params.PACKAGE_NAME ?: '(select at approval gate)'}"
                    echo "════════════════════════════════════════════════════════════"
                    dbmGetStatus([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                  server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                  password: params.DBM_PASSWORD, envName: params.ENV_NAME])
                    def pkgInfo = dbmGetPackages([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                 server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                 password: params.DBM_PASSWORD])
                    env.AVAILABLE_PACKAGES      = pkgInfo.available.join(', ') ?: '(none)'
                    env.AVAILABLE_PACKAGES_LIST = pkgInfo.available.join('\n') ?: '(none)'
                }
            }
        }

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
                          </table>
                          <br/>
                          <p style="font-size:15px;"><b>Approve or abort:</b><br/>
                            <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                          </p>
                        """
                    ])
                    if (params.PACKAGE_NAME?.trim()) {
                        input(message: "Approve upgrade of [${params.PACKAGE_NAME}] to [${params.ENV_NAME}]?", ok: 'Approve & Deploy')
                        env.SELECTED_PACKAGE = params.PACKAGE_NAME
                    } else {
                        def availableList = env.AVAILABLE_PACKAGES_LIST?.split('\n').toList() ?: []
                        if (availableList.isEmpty() || availableList == ['(none)']) {
                            error "No packages available to deploy to [${params.ENV_NAME}]."
                        }
                        def chosen = input(
                            message: "Select package to deploy to [${params.ENV_NAME}]:",
                            ok: 'Approve & Deploy',
                            parameters: [choice(name: 'PACKAGE', choices: availableList,
                                                description: 'Available packages fetched live from DBmaestro')]
                        )
                        env.SELECTED_PACKAGE = chosen
                        echo "Package selected at approval gate: ${env.SELECTED_PACKAGE}"
                    }
                }
            }
        }

        stage('Execute Upgrade') {
            steps {
                script {
                    echo "Starting DBmaestro Upgrade — Package: ${env.SELECTED_PACKAGE}"
                    dbmAgent([agentJar: params.AGENT_JAR, operation: '-Upgrade',
                              projectName: params.PROJECT_NAME, server: params.DBM_SERVER,
                              userName: params.DBM_USERNAME, password: params.DBM_PASSWORD,
                              envName: params.ENV_NAME, packageName: env.SELECTED_PACKAGE ?: 'True',
                              backupBehavior: params.BACKUP_BEHAVIOR, restoreBehavior: params.RESTORE_BEHAVIOR])
                }
            }
        }

        stage('Post-Deploy Verification') {
            steps {
                script {
                    def statusAfter = dbmGetStatus([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                    server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                    password: params.DBM_PASSWORD, envName: params.ENV_NAME])
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
                jiraComment(params.JIRA_ISSUE_KEY, "✅ *DBmaestro Upgrade succeeded*\nPackage: *${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME}* deployed to *${params.ENV_NAME}*\n[View build log|${env.BUILD_URL}console]")
            }
        }
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Upgrade [${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: 'next'}] → [${params.ENV_NAME}]",
                    type   : 'failure',
                    body   : """
                      <h3 style="margin-top:0;color:#c0392b;">Upgrade FAILED.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Environment</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.ENV_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: '(next available)'}</td></tr>
                      </table>
                      <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                    """
                ])
                if (params.TRIGGER_ROLLBACK_ON_FAILURE) {
                    echo "Queuing dbm-rollback job..."
                    build(job: 'dbm-rollback', wait: false, parameters: [
                        string(name: 'PROJECT_NAME', value: params.PROJECT_NAME),
                        string(name: 'DBM_SERVER',   value: params.DBM_SERVER),
                        string(name: 'DBM_USERNAME', value: params.DBM_USERNAME),
                        password(name: 'DBM_PASSWORD', value: params.DBM_PASSWORD),
                        string(name: 'ENV_NAME',     value: params.ENV_NAME),
                        string(name: 'PACKAGE_NAME', value: params.PACKAGE_NAME),
                        booleanParam(name: 'BACKUP_BEHAVIOR',  value: params.BACKUP_BEHAVIOR),
                        booleanParam(name: 'RESTORE_BEHAVIOR', value: params.RESTORE_BEHAVIOR),
                        string(name: 'NOTIFY_EMAIL', value: params.NOTIFY_EMAIL),
                        string(name: 'AGENT_JAR',    value: params.AGENT_JAR)
                    ])
                }
                jiraComment(params.JIRA_ISSUE_KEY, "❌ *DBmaestro Upgrade FAILED*\nPackage: *${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME}* → *${params.ENV_NAME}*\n[View build log|${env.BUILD_URL}console]")
            }
        }
        aborted {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "ABORTED – Upgrade [${env.SELECTED_PACKAGE ?: params.PACKAGE_NAME ?: 'next'}] → [${params.ENV_NAME}]",
                    type   : 'info',
                    body   : '<p>The upgrade was aborted. No changes were made.</p>'
                ])
            }
        }
    }
}
