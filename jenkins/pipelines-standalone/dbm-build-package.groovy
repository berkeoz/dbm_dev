// =============================================================================
// dbm-build-package.groovy – Self-contained, no shared library required
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
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
        ansiColor('xterm')
    }

    parameters {
        string(name: 'PROJECT_NAME', defaultValue: 'DEMO_AS_ORACLE',       description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',   defaultValue: 'WIN-HM6PVCVCPCB:8017', description: 'DBmaestro server host:port')
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',                    description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo',    description: 'DBmaestro account password')
        string(name: 'PACKAGE_NAME', defaultValue: '', description: 'New package name (e.g. V7). Leave blank to auto-increment.')
        choice(name: 'PACKAGE_TYPE', choices: ['Regular', 'AdHoc'], description: 'Regular = standard package. AdHoc = one-off script bundle.')
        string(name: 'PACKAGE_DESCRIPTION', defaultValue: '', description: 'Optional description for the package')
        string(name: 'SOURCE_ENV', defaultValue: 'Release Source', description: 'Source environment to build the package from')
        string(name: 'BRANCH_NAME', defaultValue: 'rs', description: 'Source control branch name (e.g. rs, main, master)')
        choice(name: 'VERSION_TYPE', choices: ['Latest Revision', 'Label'], description: 'Version type for the build command')
        booleanParam(name: 'CREATE_DOWNGRADE_SCRIPTS', defaultValue: true, description: 'Generate downgrade scripts as part of the build')
        string(name: 'EXTRA_ARGS', defaultValue: '', description: 'Extra CLI arguments passed verbatim to DBmaestroAgent.jar')
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com', description: 'Notification recipients')
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar')
        booleanParam(name: 'TRIGGER_UPGRADE_AFTER_BUILD', defaultValue: false,
                     description: 'Queue dbm-upgrade job after a successful build')
    }

    stages {

        stage('Pre-Flight – List Existing Packages') {
            steps {
                script {
                    def pkgInfo = dbmGetPackages([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                 server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                 password: params.DBM_PASSWORD])
                    env.EXISTING_PACKAGES  = pkgInfo.all.join(', ')       ?: '(none)'
                    env.AVAILABLE_PACKAGES = pkgInfo.available.join(', ') ?: '(none)'

                    def pkgName = params.PACKAGE_NAME?.trim() ?: ''
                    if (!pkgName) {
                        def maxVersion = 0
                        pkgInfo.all.each { name ->
                            if (name.startsWith('V') && name.length() > 1) {
                                def numStr = name.substring(1).split('[^0-9]')[0]
                                if (numStr.isInteger()) {
                                    def v = numStr as Integer
                                    if (v > maxVersion) { maxVersion = v }
                                }
                            }
                        }
                        pkgName = "V${maxVersion + 1}"
                        echo "Auto-generated package name: ${pkgName} (previous highest: V${maxVersion})"
                    }
                    env.COMPUTED_PACKAGE_NAME = pkgName

                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro BUILD PACKAGE  |  ${params.PROJECT_NAME}"
                    echo "  New package : ${env.COMPUTED_PACKAGE_NAME}"
                    echo "  Type        : ${params.PACKAGE_TYPE}"
                    echo "════════════════════════════════════════════════════════════"

                    if (pkgInfo.all.contains(env.COMPUTED_PACKAGE_NAME)) {
                        error "Package '${env.COMPUTED_PACKAGE_NAME}' already exists. Choose a different name."
                    }
                }
            }
        }

        stage('Approval Gate') {
            steps {
                script {
                    dbmNotify([
                        to     : params.NOTIFY_EMAIL,
                        subject: "APPROVAL REQUIRED – Build package [${env.COMPUTED_PACKAGE_NAME}] in [${params.PROJECT_NAME}]",
                        type   : 'approval',
                        body   : """
                          <h3 style="margin-top:0;">A new package build is awaiting your approval.</h3>
                          <table width="100%" style="border-collapse:collapse;font-size:14px;">
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>New package name</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.COMPUTED_PACKAGE_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package type</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_TYPE}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Source environment</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.SOURCE_ENV}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Branch</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.BRANCH_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Version type</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.VERSION_TYPE}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Existing packages</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.EXISTING_PACKAGES}</td></tr>
                          </table>
                          <br/>
                          <p style="font-size:15px;"><b>Approve or abort:</b><br/>
                            <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                          </p>
                        """
                    ])
                    input(message: "Approve creation of package [${env.COMPUTED_PACKAGE_NAME}] in [${params.PROJECT_NAME}]?",
                          ok: 'Approve & Build')
                }
            }
        }

        stage('Build Package') {
            steps {
                script {
                    echo "Creating DBmaestro package..."
                    def extra = []
                    extra << '-CreatePackage'          << 'True'
                    extra << '-CreateDowngradeScripts' << (params.CREATE_DOWNGRADE_SCRIPTS ? 'True' : 'False')
                    extra << '-VersionType'            << "\"${params.VERSION_TYPE}\""
                    extra << '-BranchName'             << "\"${params.BRANCH_NAME}\""
                    if (params.PACKAGE_TYPE == 'AdHoc') { extra << '-IsAdhoc' << 'True' }
                    if (params.PACKAGE_DESCRIPTION?.trim()) { extra << '-Description' << "\"${params.PACKAGE_DESCRIPTION.trim()}\"" }
                    if (params.EXTRA_ARGS?.trim()) { extra.addAll(params.EXTRA_ARGS.trim().split(/\s+/).toList()) }
                    dbmAgent([agentJar: params.AGENT_JAR, operation: '-Build',
                              projectName: params.PROJECT_NAME, server: params.DBM_SERVER,
                              userName: params.DBM_USERNAME, password: params.DBM_PASSWORD,
                              envName: params.SOURCE_ENV, packageName: env.COMPUTED_PACKAGE_NAME,
                              extraArgs: extra])
                }
            }
        }

        stage('Post-Build Verification') {
            steps {
                script {
                    def pkgInfoAfter = dbmGetPackages([agentJar: params.AGENT_JAR, projectName: params.PROJECT_NAME,
                                                      server: params.DBM_SERVER, userName: params.DBM_USERNAME,
                                                      password: params.DBM_PASSWORD])
                    if (!pkgInfoAfter.all.contains(env.COMPUTED_PACKAGE_NAME)) {
                        echo "WARNING: Package '${env.COMPUTED_PACKAGE_NAME}' not found after build. Check DBmaestro UI."
                    } else {
                        echo "Package '${env.COMPUTED_PACKAGE_NAME}' confirmed. Available for deployment."
                    }
                    env.ALL_PACKAGES_AFTER = pkgInfoAfter.all.join(', ')
                }
            }
        }
    }

    post {
        success {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "SUCCESS – Package [${env.COMPUTED_PACKAGE_NAME}] built in [${params.PROJECT_NAME}]",
                    type   : 'success',
                    body   : """
                      <h3 style="margin-top:0;color:#27ae60;">Package built successfully.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package created</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.COMPUTED_PACKAGE_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Type</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_TYPE}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>All packages now</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.ALL_PACKAGES_AFTER}</td></tr>
                      </table>
                    """
                ])
                if (params.TRIGGER_UPGRADE_AFTER_BUILD) {
                    echo "Queuing dbm-upgrade job..."
                    build(job: 'dbm-upgrade', wait: false, parameters: [
                        string(name: 'PROJECT_NAME', value: params.PROJECT_NAME),
                        string(name: 'DBM_SERVER',   value: params.DBM_SERVER),
                        string(name: 'DBM_USERNAME', value: params.DBM_USERNAME),
                        password(name: 'DBM_PASSWORD', value: params.DBM_PASSWORD),
                        string(name: 'ENV_NAME',     value: params.SOURCE_ENV),
                        string(name: 'PACKAGE_NAME', value: env.COMPUTED_PACKAGE_NAME),
                        string(name: 'NOTIFY_EMAIL', value: params.NOTIFY_EMAIL),
                        string(name: 'AGENT_JAR',    value: params.AGENT_JAR)
                    ])
                }
            }
        }
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Package build [${env.COMPUTED_PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]",
                    type   : 'failure',
                    body   : """
                      <h3 style="margin-top:0;color:#c0392b;">Package build FAILED.</h3>
                      <p>No package was created. Review the console output for errors.</p>
                      <br/><a href="${env.BUILD_URL}console" style="color:#c0392b;">View Console Output</a>
                    """
                ])
            }
        }
        aborted {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "ABORTED – Package build [${env.COMPUTED_PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]",
                    type   : 'info',
                    body   : '<p>Package build was aborted. No package was created.</p>'
                ])
            }
        }
    }
}
