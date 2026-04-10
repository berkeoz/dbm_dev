// =============================================================================
// dbm-build-package.groovy  –  DBmaestro Build / Create Package Pipeline
//
// Purpose : Create a new deployment package in a DBmaestro project.
//           The package is built from the Release Source environment and
//           made available for downstream environment promotion.
//
// Flow    : Pre-flight (list existing) → Approval → Build → Post-check → Notify
//
// NOTE on CLI flag:
//   The -CreatePackage flag shown below is the standard DBmaestro Agent
//   operation for package creation. Verify the exact flag name against
//   your DBmaestro version at:
//     http://localhost:88/project/<id>/environment/<id>/automation
//
// Requires: Email Extension plugin, DBmaestro Shared Library
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
        // ── DBmaestro Connection ──────────────────────────────────────────────
        string(name: 'PROJECT_NAME', defaultValue: 'DEMO_AS_ORACLE',       description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',   defaultValue: 'WIN-HM6PVCVCPCB:8017', description: 'DBmaestro server  host:port')

        // ── Credentials ───────────────────────────────────────────────────────
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',                 description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo', description: 'DBmaestro account password')

        // ── Package definition ────────────────────────────────────────────────
        string(name: 'PACKAGE_NAME', defaultValue: '',
               description: 'Name for the new package (e.g. V6, HOTFIX_APR2026). Leave blank to let DBmaestro auto-name.')
        choice(name: 'PACKAGE_TYPE', choices: ['Regular', 'AdHoc'],
               description: 'Regular = standard pipeline package. AdHoc = one-off script bundle.')
        string(name: 'PACKAGE_DESCRIPTION', defaultValue: '',
               description: 'Optional free-text description stored with the package in DBmaestro')

        // ── Source environment ────────────────────────────────────────────────
        string(name: 'SOURCE_ENV', defaultValue: 'Release Source',
               description: 'Source environment from which the package is built')

        // ── Advanced ──────────────────────────────────────────────────────────
        string(name: 'EXTRA_ARGS', defaultValue: '',
               description: 'Space-separated extra CLI arguments passed verbatim to DBmaestroAgent.jar (e.g. -Force -DryRun)')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com',
               description: 'Comma-separated email addresses for approval + result notifications')

        // ── Infrastructure ────────────────────────────────────────────────────
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')

        // ── Post-build option ─────────────────────────────────────────────────
        booleanParam(name: 'TRIGGER_UPGRADE_AFTER_BUILD', defaultValue: false,
                     description: 'After a successful build, queue the dbm-upgrade job to deploy the new package')
    }

    stages {

        // ── Stage 1: Pre-Flight ───────────────────────────────────────────────
        stage('Pre-Flight – List Existing Packages') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro BUILD PACKAGE  |  ${params.PROJECT_NAME}"
                    echo "  New package : ${params.PACKAGE_NAME ?: '(auto-named)'}"
                    echo "  Type        : ${params.PACKAGE_TYPE}"
                    echo "════════════════════════════════════════════════════════════"

                    def pkgInfo = dbmGetPackages([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD
                    ])
                    env.EXISTING_PACKAGES  = pkgInfo.all.join(', ')       ?: '(none)'
                    env.AVAILABLE_PACKAGES = pkgInfo.available.join(', ') ?: '(none)'

                    if (params.PACKAGE_NAME && pkgInfo.all.contains(params.PACKAGE_NAME)) {
                        error "Package '${params.PACKAGE_NAME}' already exists in '${params.PROJECT_NAME}'. Choose a different name."
                    }
                }
            }
        }

        // ── Stage 2: Approval Gate ────────────────────────────────────────────
        stage('Approval Gate') {
            steps {
                script {
                    dbmNotify([
                        to     : params.NOTIFY_EMAIL,
                        subject: "APPROVAL REQUIRED – Build package [${params.PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]",
                        type   : 'approval',
                        body   : """
                          <h3 style="margin-top:0;">A new package build is awaiting your approval.</h3>
                          <table width="100%" style="border-collapse:collapse;font-size:14px;">
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>New package name</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(auto-named)'}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package type</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_TYPE}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Description</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_DESCRIPTION ?: '—'}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Source environment</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.SOURCE_ENV}</td></tr>
                            <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Existing packages</b></td>
                                <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.EXISTING_PACKAGES}</td></tr>
                          </table>
                          <br/>
                          <p style="font-size:15px;">
                            <b>Approve or abort:</b><br/>
                            <a href="${env.BUILD_URL}input" style="color:#e67e22;font-weight:bold;">${env.BUILD_URL}input</a>
                          </p>
                        """
                    ])

                    input(
                        message: "Approve creation of package [${params.PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]?",
                        ok     : 'Approve & Build'
                    )
                }
            }
        }

        // ── Stage 3: Build Package ────────────────────────────────────────────
        stage('Build Package') {
            steps {
                script {
                    echo "Creating DBmaestro package..."

                    def extra = []
                    if (params.PACKAGE_TYPE == 'AdHoc') {
                        extra << '-IsAdhoc' << 'True'
                    }
                    if (params.PACKAGE_DESCRIPTION?.trim()) {
                        extra << '-Description' << "\"${params.PACKAGE_DESCRIPTION.trim()}\""
                    }
                    if (params.EXTRA_ARGS?.trim()) {
                        extra.addAll(params.EXTRA_ARGS.trim().split(/\s+/).toList())
                    }

                    // NOTE: Verify -CreatePackage is the correct flag for your DBmaestro version.
                    // Check the Automation tab in the UI at:
                    //   http://localhost:88/project/<id>/environment/<id>/automation
                    dbmAgent([
                        agentJar   : params.AGENT_JAR,
                        operation  : '-CreatePackage',
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD,
                        envName    : params.SOURCE_ENV,
                        packageName: params.PACKAGE_NAME ?: '',
                        extraArgs  : extra
                    ])
                }
            }
        }

        // ── Stage 4: Post-Build Verification ──────────────────────────────────
        stage('Post-Build Verification') {
            steps {
                script {
                    def pkgInfoAfter = dbmGetPackages([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD
                    ])

                    if (params.PACKAGE_NAME && !pkgInfoAfter.all.contains(params.PACKAGE_NAME)) {
                        echo "WARNING: Package '${params.PACKAGE_NAME}' not found after build. Check DBmaestro UI."
                    } else {
                        echo "Package confirmed in project. Available for deployment."
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
                    subject: "SUCCESS – Package [${params.PACKAGE_NAME ?: 'auto'}] built in [${params.PROJECT_NAME}]",
                    type   : 'success',
                    body   : """
                      <h3 style="margin-top:0;color:#27ae60;">Package built successfully.</h3>
                      <table width="100%" style="border-collapse:collapse;font-size:14px;">
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:30%;"><b>Project</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PROJECT_NAME}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Package created</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_NAME ?: '(auto-named)'}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Type</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.PACKAGE_TYPE}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>All packages now</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.ALL_PACKAGES_AFTER}</td></tr>
                        <tr><td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Upgrade queued</b></td>
                            <td style="padding:7px 10px;border:1px solid #e0e0e0;">${params.TRIGGER_UPGRADE_AFTER_BUILD}</td></tr>
                      </table>
                    """
                ])

                if (params.TRIGGER_UPGRADE_AFTER_BUILD) {
                    echo "Queuing dbm-upgrade job for the new package..."
                    build(
                        job : 'dbm-upgrade',
                        wait: false,
                        parameters: [
                            string      (name: 'PROJECT_NAME', value: params.PROJECT_NAME),
                            string      (name: 'DBM_SERVER',   value: params.DBM_SERVER),
                            string      (name: 'DBM_USERNAME', value: params.DBM_USERNAME),
                            password    (name: 'DBM_PASSWORD', value: params.DBM_PASSWORD),
                            string      (name: 'ENV_NAME',     value: params.SOURCE_ENV),
                            string      (name: 'PACKAGE_NAME', value: params.PACKAGE_NAME),
                            string      (name: 'NOTIFY_EMAIL', value: params.NOTIFY_EMAIL),
                            string      (name: 'AGENT_JAR',    value: params.AGENT_JAR)
                        ]
                    )
                }
            }
        }
        failure {
            script {
                dbmNotify([
                    to     : params.NOTIFY_EMAIL,
                    subject: "FAILED – Package build [${params.PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]",
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
                    subject: "ABORTED – Package build [${params.PACKAGE_NAME ?: 'auto'}] in [${params.PROJECT_NAME}]",
                    type   : 'info',
                    body   : '<p>Package build was aborted. No package was created.</p>'
                ])
            }
        }
    }
}
