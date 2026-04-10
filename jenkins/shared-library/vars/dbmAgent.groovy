#!/usr/bin/env groovy
// =============================================================================
// dbmAgent.groovy  –  Shared Library: Core DBmaestroAgent.jar runner
//
// Builds the CLI command, masks the password in console output, executes via
// bat (Windows) or sh (Linux), and throws an error on non-zero exit codes.
//
// Caller supplies a Map with:
//   Required:
//     agentJar      – full path to DBmaestroAgent.jar
//     operation     – CLI flag: -Upgrade, -Rollback, -GetPackages, etc.
//     projectName   – DBmaestro project name
//     server        – host:port  (e.g. WIN-HM6PVCVCPCB:8017)
//     userName      – DBmaestro account username
//     password      – DBmaestro account password
//   Optional:
//     authType      – default: DBmaestroAccount
//     envName       – target environment
//     packageName   – package to act on
//     backupBehavior  – true/false
//     restoreBehavior – true/false
//     filePath      – output JSON file path
//     extraArgs     – List<String> of additional raw CLI args
//     isLinux       – boolean, switches bat→sh (default: false)
//
// Returns: int exit code (always 0; throws on failure)
// =============================================================================

def call(Map cfg) {

    def authType    = cfg.get('authType', 'DBmaestroAccount')
    def isLinux     = cfg.get('isLinux', false)

    // ── Build argument list ──────────────────────────────────────────────────
    List args = []
    args << "java" << "-jar" << "\"${cfg.agentJar}\""
    args << cfg.operation
    args << "-ProjectName" << "\"${cfg.projectName}\""
    args << "-Server"      << "\"${cfg.server}\""
    args << "-AuthType"    << authType
    args << "-UserName"    << "\"${cfg.userName}\""
    args << "-Password"    << "\"${cfg.password}\""

    if (cfg.envName)          { args << "-EnvName"          << "\"${cfg.envName}\""   }
    if (cfg.packageName)      { args << "-PackageName"      << "\"${cfg.packageName}\"" }
    if (cfg.backupBehavior  != null) { args << "-BackupBehavior"  << (cfg.backupBehavior  ? 'True' : 'False') }
    if (cfg.restoreBehavior != null) { args << "-RestoreBehavior" << (cfg.restoreBehavior ? 'True' : 'False') }
    if (cfg.filePath)         { args << "-FilePath"         << "\"${cfg.filePath}\""  }
    if (cfg.extraArgs)        { args.addAll(cfg.extraArgs)                              }

    def cmd       = args.join(' ')
    def maskedCmd = cmd.replace(cfg.password.toString(), '********')
    echo "DBmaestro Agent ► ${maskedCmd}"

    // ── Execute ──────────────────────────────────────────────────────────────
    def exitCode = isLinux
        ? sh(script: cmd, returnStatus: true)
        : bat(script: cmd, returnStatus: true)

    if (exitCode != 0) {
        error "DBmaestroAgent.jar exited ${exitCode} for operation: ${cfg.operation}"
    }
    return exitCode
}
