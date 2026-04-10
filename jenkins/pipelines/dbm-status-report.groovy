// =============================================================================
// dbm-status-report.groovy  –  DBmaestro Status & Information Pipeline
//
// Purpose : Read-only. Reports current deployed version, deployment history,
//           and available packages for each environment. No changes are made.
//
// Requires: Email Extension plugin, DBmaestro Shared Library (dbmAgent, dbmGetStatus,
//           dbmGetPackages, dbmNotify registered under Global Pipeline Libraries)
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
        // ── DBmaestro Connection ──────────────────────────────────────────────
        string(name: 'PROJECT_NAME',  defaultValue: 'DEMO_AS_ORACLE',          description: 'DBmaestro project name (case-sensitive)')
        string(name: 'DBM_SERVER',    defaultValue: 'WIN-HM6PVCVCPCB:8017',    description: 'DBmaestro server  host:port')

        // ── Credentials (use Jenkins Credential Store in production) ──────────
        string(  name: 'DBM_USERNAME', defaultValue: 'poc@dbmaestro.com',              description: 'DBmaestro account username')
        password(name: 'DBM_PASSWORD', defaultValue: 'CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo', description: 'DBmaestro account password')

        // ── Scope ─────────────────────────────────────────────────────────────
        string(name: 'ENVIRONMENTS', defaultValue: '',
               description: 'Comma-separated environment names to report on. Leave blank for ALL environments.')
        booleanParam(name: 'INCLUDE_PACKAGE_LIST', defaultValue: true,
                     description: 'Include the full project package list in the report')
        booleanParam(name: 'SEND_EMAIL_REPORT',    defaultValue: true,
                     description: 'Email the report summary on completion')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name: 'NOTIFY_EMAIL', defaultValue: 'berkeo@dbmaestro.com',
               description: 'Comma-separated email addresses for the report')

        // ── Infrastructure ────────────────────────────────────────────────────
        string(name: 'AGENT_JAR',
               defaultValue: 'C:\\Program Files (x86)\\DBmaestro\\DOP Server\\Agent\\DBmaestroAgent.jar',
               description: 'Full path to DBmaestroAgent.jar on the Jenkins agent')
    }

    stages {

        // ── Stage 1: Project Package List ─────────────────────────────────────
        stage('Fetch Project Packages') {
            when { expression { return params.INCLUDE_PACKAGE_LIST } }
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════"
                    echo "  DBmaestro STATUS REPORT  |  ${params.PROJECT_NAME}"
                    echo "════════════════════════════════════════════════════════════"

                    def pkgInfo = dbmGetPackages([
                        agentJar   : params.AGENT_JAR,
                        projectName: params.PROJECT_NAME,
                        server     : params.DBM_SERVER,
                        userName   : params.DBM_USERNAME,
                        password   : params.DBM_PASSWORD
                    ])

                    env.PKG_AVAILABLE = pkgInfo.available.join(', ') ?: '(none)'
                    env.PKG_ALL       = pkgInfo.all.join(', ')       ?: '(none)'
                }
            }
        }

        // ── Stage 2: Discover Environments ────────────────────────────────────
        stage('Discover Environments') {
            steps {
                script {
                    def tmpFile = "${env.WORKSPACE}\\dbm_project_data_${System.currentTimeMillis()}.json"
                    try {
                        dbmAgent([
                            agentJar   : params.AGENT_JAR,
                            operation  : '-GetProjectData',
                            projectName: params.PROJECT_NAME,
                            server     : params.DBM_SERVER,
                            userName   : params.DBM_USERNAME,
                            password   : params.DBM_PASSWORD,
                            filePath   : tmpFile
                        ])

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

        // ── Stage 3: Per-Environment Status ───────────────────────────────────
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

                        def statusData = dbmGetStatus([
                            agentJar   : params.AGENT_JAR,
                            projectName: params.PROJECT_NAME,
                            server     : params.DBM_SERVER,
                            userName   : params.DBM_USERNAME,
                            password   : params.DBM_PASSWORD,
                            envName    : envName
                        ])

                        def currentPkg = statusData.find { pkg ->
                            pkg.EnvDeployed && !pkg.EnvDeployed.toString().trim().isEmpty()
                        }
                        def currentVersion = currentPkg
                            ? (currentPkg.VersionName ?: currentPkg.Name)
                            : '(nothing deployed)'

                        def deployedCount  = statusData.count { it.EnvDeployed && !it.EnvDeployed.toString().trim().isEmpty() }
                        def availableCount = statusData.count {
                            it.RSDeployed  && !it.RSDeployed.toString().trim().isEmpty() &&
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
