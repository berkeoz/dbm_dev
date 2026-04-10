#!/usr/bin/env groovy
// =============================================================================
// dbmGetPackages.groovy  –  Shared Library: Project-level package list
//
// Calls -GetPackages and returns two lists:
//   result.available – packages with State=0 (ready to deploy to Release Source)
//   result.all       – every package returned by the agent
//
// Required keys in cfg Map:
//   agentJar, projectName, server, userName, password
// Optional:
//   isLinux (default false)
// =============================================================================

def call(Map cfg) {

    def isLinux = cfg.get('isLinux', false)
    def tmpFile = "${env.WORKSPACE}${isLinux ? '/' : '\\'}dbm_packages_${System.currentTimeMillis()}.json"

    try {
        dbmAgent([
            agentJar   : cfg.agentJar,
            operation  : '-GetPackages',
            projectName: cfg.projectName,
            server     : cfg.server,
            userName   : cfg.userName,
            password   : cfg.password,
            filePath   : tmpFile,
            isLinux    : isLinux
        ])

        def json     = readFile(file: tmpFile).trim()
        def allPkgs  = readJSON(text: json)

        // Available = State=0, enabled, not temporary, not adhoc
        def available = allPkgs.findAll { p ->
            p.State          == 0    &&
            p.IsEnabled      == true &&
            p.IsTemporary    == false &&
            p.IsAdhocPackage == false
        }.sort { it.Id }

        echo "┌─ Packages for [${cfg.projectName}] ──────────────────────────────────"
        echo "│  Total: ${allPkgs.size()}   Available: ${available.size()}"
        available.each { p -> echo "│    ✓ ${p.Name}" }
        if (available.isEmpty()) { echo "│    (none – project is at the latest package)" }
        echo "└──────────────────────────────────────────────────────────────────"

        return [
            available: available.collect { it.Name as String },
            all      : allPkgs.collect  { it.Name as String }
        ]

    } catch (Exception ex) {
        echo "WARNING: Could not list packages for [${cfg.projectName}]: ${ex.message}"
        return [available: [], all: []]

    } finally {
        if (isLinux) {
            sh  "rm -f \"${tmpFile}\" 2>/dev/null || true"
        } else {
            bat "if exist \"${tmpFile}\" del /f /q \"${tmpFile}\""
        }
    }
}
