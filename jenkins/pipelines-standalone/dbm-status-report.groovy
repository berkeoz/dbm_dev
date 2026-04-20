// =============================================================================
// dbm-status-report.groovy – Self-contained, no shared library required
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
    if (cfg.envName)     { args << "-EnvName"     << "\"${cfg.envName}\""     }
    if (cfg.packageName) { args << "-PackageName" << "\"${cfg.packageName}\"" }
    if (cfg.filePath)    { args << "-FilePath"    << "\"${cfg.filePath}\""    }
    if (cfg.extraArgs)   { args.addAll(cfg.extraArgs) }
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

// =============================================================================
// Pipeline
// =============================================================================
pipeline {
    agent { label 'windows' }

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '50'))
        timestamps()
        ansiColor('xterm')
    }

    parameters {
        string(name: 'PROJECT_NAME', defaultValue: 'DEMO_AS_ORACLE',       description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',   defaultValue: 'WIN-HM6PVCVCPCB:8017', description: 'DBmaestro server  host:port')
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',                    description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo',    description: 'DBmaestro account password')
        string(name: 'ENVIRONMENTS', defaultValue: '',
               description: 'Comma-separated environment names to report on. Leave blank for ALL environments.')
        booleanParam(name: 'INCLUDE_PACKAGE_LIST', defaultValue: true,  description: 'Include the full project package list in the report')
        booleanParam(name: 'SEND_EMAIL_REPORT',    defaultValue: true,  description: 'Email the report summary on completion')
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com', description: 'Comma-separated email addresses for the report')
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')
    }

    stages {

        stage('Fetch Project Packages') {
            when { expression { return params.INCLUDE_PACKAGE_LIST } }
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro STATUS REPORT  |  ${params.PROJECT_NAME}"
                    echo "════════════════════════════════════════════════════════════"
                    def pkgInfo = dbmGetPackages([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                 server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                 password: params.DBM_PASSWORD])
                    env.PKG_AVAILABLE = pkgInfo.available.join(', ') ?: '(none)'
                    env.PKG_ALL       = pkgInfo.all.join(', ')       ?: '(none)'
                }
            }
        }

        stage('Discover Environments') {
            steps {
                script {
                    def tmpFile = "${env.WORKSPACE}\\dbm_project_data_${System.currentTimeMillis()}.json"
                    try {
                        dbmAgent([agentJar: params.AGENT_JAR, operation: '-GetProjectData',
                                  projectName: params.PROJECT_NAME, server: params.DBM_SERVER,
                                  userName: params.DBM_USERNAME, password: params.DBM_PASSWORD,
                                  filePath: tmpFile])
                        def projectData = readJSON(file: tmpFile)
                        def allEnvNames = []
                        projectData.EnvironmentTypes?.each { envType ->
                            envType.Environments?.each { e -> allEnvNames << e.Name }
                        }
                        echo "Project environments discovered: ${allEnvNames.join(', ')}"
                        def scopeList = params.ENVIRONMENTS?.trim()
                            ? params.ENVIRONMENTS.split(',').collect { it.trim() }.findAll { it }
                            : allEnvNames
                        env.RESOLVED_ENVS = scopeList.join(',')
                        echo "Reporting on: ${env.RESOLVED_ENVS}"
                    } finally {
                        bat "if exist \"${tmpFile}\" del /f /q \"${tmpFile}\""
                    }
                }
            }
        }

        stage('Environment Status') {
            steps {
                script {
                    def envList    = env.RESOLVED_ENVS?.split(',')?.collect { it.trim() } ?: []
                    def reportRows = []
                    envList.each { envName ->
                        echo ""
                        echo "────────────────────────────────────────────────────────"
                        echo "  Querying: ${envName}"
                        echo "────────────────────────────────────────────────────────"
                        def statusData = dbmGetStatus([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                       server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                       password: params.DBM_PASSWORD, envName: envName])
                        def currentPkg = statusData.find { pkg ->
                            pkg.EnvDeployed && !pkg.EnvDeployed.toString().trim().isEmpty()
                        }
                        def currentVersion = currentPkg ? (currentPkg.VersionName ?: currentPkg.Name) : '(nothing deployed)'
                        def deployedCount  = statusData.count { it.EnvDeployed && !it.EnvDeployed.toString().trim().isEmpty() }
                        def availableCount = statusData.count {
                            it.RSDeployed && !it.RSDeployed.toString().trim().isEmpty() &&
                            (!it.EnvDeployed || it.EnvDeployed.toString().trim().isEmpty())
                        }
                        reportRows << [env: envName, current: currentVersion, deployed: deployedCount, available: availableCount]
                    }
                    echo ""
                    echo "════════════════════════════════════════════════════════════"
                    echo "  SUMMARY"
                    echo "  ${'Environment'.padRight(25)}  ${'Current Version'.padRight(25)}  Deployed  Available"
                    echo "  ${'─' * 25}  ${'─' * 25}  ────────  ─────────"
                    reportRows.each { row ->
                        echo "  ${row.env.padRight(25)}  ${row.current.toString().padRight(25)}  ${row.deployed.toString().padRight(8)}  ${row.available}"
                    }
                    echo "════════════════════════════════════════════════════════════"
                    def htmlRows = reportRows.collect { row ->
                        "<tr>" +
                        "<td style='padding:7px 10px;border:1px solid #e0e0e0;'>${row.env}</td>" +
                        "<td style='padding:7px 10px;border:1px solid #e0e0e0;'><b>${row.current}</b></td>" +
                        "<td style='padding:7px 10px;border:1px solid #e0e0e0;text-align:center;'>${row.deployed}</td>" +
                        "<td style='padding:7px 10px;border:1px solid #e0e0e0;text-align:center;'>${row.available}</td>" +
                        "</tr>"
                    }.join('\n')
                    env.REPORT_HTML_ROWS = htmlRows
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.SEND_EMAIL_REPORT) {
                    def pkgSection = params.INCLUDE_PACKAGE_LIST ? """
                      <h4>Project Package Summary</h4>
                      <table width="100%" style="border-collapse:collapse;font-size:13px;margin-bottom:12px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Available (undeployed)</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.PKG_AVAILABLE ?: '—'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>All packages</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.PKG_ALL ?: '—'}</td></tr>
                      </table>
                    """ : ''
                    dbmNotify([
                        to     : params.NOTIFY_EMAIL,
                        subject: "Status Report – ${params.PROJECT_NAME} (${new Date().format('yyyy-MM-dd HH:mm')})",
                        type   : 'info',
                        body   : """
                          <h3 style="margin-top:0;">Environment Status Report</h3>
                          <p>Project: <b>${params.PROJECT_NAME}</b> &nbsp;|&nbsp; Server: ${params.DBM_SERVER}</p>
                          ${pkgSection}
                          <h4>Environment Summary</h4>
                          <table width="100%" style="border-collapse:collapse;font-size:13px;">
                            <tr style="background:#2980b9;color:#fff;">
                              <th style="padding:8px 10px;text-align:left;">Environment</th>
                              <th style="padding:8px 10px;text-align:left;">Current Version</th>
                              <th style="padding:8px 10px;text-align:center;">Deployed Count</th>
                              <th style="padding:8px 10px;text-align:center;">Available Count</th>
                            </tr>
                            ${env.REPORT_HTML_ROWS ?: '<tr><td colspan="4" style="padding:8px 10px;">No data</td></tr>'}
                          </table>
                          <p style="font-size:12px;color:#888;margin-top:12px;">
                            This is an informational report. No changes were made to any environment.
                          </p>
                        """
                    ])
                }
            }
        }
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Status Report for [${params.PROJECT_NAME}]",
                    type   : 'failure',
                    body   : """
                      <p>The status report job failed. One or more DBmaestro Agent calls returned an error.</p>
                      <p>This is likely a connectivity or authentication issue. No environment changes were made.</p>
                      <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                    """
                ])
            }
        }
    }
}
