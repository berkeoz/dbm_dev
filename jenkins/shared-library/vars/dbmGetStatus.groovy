#!/usr/bin/env groovy
// =============================================================================
// dbmGetStatus.groovy  –  Shared Library: Per-environment package status
//
// Calls -GetEnvPackages, prints a formatted summary table, and returns the
// raw parsed JSON list so callers can inspect it programmatically.
//
// Required keys in cfg Map:
//   agentJar, projectName, server, userName, password, envName
// Optional:
//   isLinux (default false)
//
// Returns: List of package objects (empty list on error)
// =============================================================================

def call(Map cfg) {

    def isLinux  = cfg.get('isLinux', false)
    def safeEnv  = cfg.envName.replaceAll('[^a-zA-Z0-9_-]', '_')
    def tmpFile  = "${env.WORKSPACE}${isLinux ? '/' : '\\'}dbm_status_${safeEnv}_${System.currentTimeMillis()}.json"

    try {
        dbmAgent([
            agentJar   : cfg.agentJar,
            operation  : '-GetEnvPackages',
            projectName: cfg.projectName,
            server     : cfg.server,
            userName   : cfg.userName,
            password   : cfg.password,
            envName    : cfg.envName,
            filePath   : tmpFile,
            isLinux    : isLinux
        ])

        def json = readFile(file: tmpFile).trim()
        def data = readJSON(text: json)

        // ── Format summary table ─────────────────────────────────────────────
        echo "┌─ Status: [${cfg.envName}] ─────────────────────────────────────────"
        data.each { pkg ->
            def name     = (pkg.VersionName ?: pkg.Name) as String
            def deployed = pkg.EnvDeployed  ?: '—'
            def rsDate   = pkg.RSDeployed   ?: '—'
            echo "│  ${name.padRight(20)}  EnvDeployed: ${deployed.padRight(25)}  RSDeployed: ${rsDate}"
        }
        echo "└──────────────────────────────────────────────────────────────────"

        return data

    } catch (Exception ex) {
        echo "WARNING: Could not retrieve status for [${cfg.envName}]: ${ex.message}"
        return []
    } finally {
        // Clean up temp file regardless of outcome
        if (isLinux) {
            sh  "rm -f \"${tmpFile}\"  2>/dev/null || true"
        } else {
            bat "if exist \"${tmpFile}\" del /f /q \"${tmpFile}\""
        }
    }
}
