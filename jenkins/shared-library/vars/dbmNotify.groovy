#!/usr/bin/env groovy
// =============================================================================
// dbmNotify.groovy  –  Shared Library: Email notification helper
//
// Sends a styled HTML email via the Email Extension (emailext) plugin.
//
// Required keys in cfg Map:
//   to      – recipient(s), comma-separated
//   subject – short subject line (label prefix added automatically)
//   type    – 'approval' | 'success' | 'failure' | 'info'
// Optional:
//   body    – HTML fragment injected into the message body
// =============================================================================

def call(Map cfg) {

    def styles = [
        approval: [color: '#e67e22', label: 'ACTION REQUIRED'],
        success : [color: '#27ae60', label: 'SUCCESS'        ],
        failure : [color: '#c0392b', label: 'FAILED'         ],
        info    : [color: '#2980b9', label: 'INFO'           ]
    ]
    def style = styles.get(cfg.type, styles.info)

    def html = """<!DOCTYPE html>
<html>
<body style="margin:0;padding:0;font-family:Arial,Helvetica,sans-serif;background:#f4f4f4;">
  <table width="100%" cellpadding="0" cellspacing="0">
    <tr>
      <td align="center" style="padding:20px 0;">
        <table width="680" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:6px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.12);">

          <!-- Header -->
          <tr>
            <td style="background:${style.color};padding:18px 28px;">
              <span style="color:#fff;font-size:20px;font-weight:bold;">[${style.label}] DBmaestro Pipeline</span>
            </td>
          </tr>

          <!-- Meta table -->
          <tr>
            <td style="padding:20px 28px 0;">
              <table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:14px;">
                <tr>
                  <td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;width:25%;"><b>Job</b></td>
                  <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.JOB_NAME}</td>
                </tr>
                <tr>
                  <td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Build #</b></td>
                  <td style="padding:7px 10px;border:1px solid #e0e0e0;">${env.BUILD_NUMBER}</td>
                </tr>
                <tr>
                  <td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Triggered by</b></td>
                  <td style="padding:7px 10px;border:1px solid #e0e0e0;">${currentBuild.getBuildCauses()[0]?.shortDescription ?: 'n/a'}</td>
                </tr>
                <tr>
                  <td style="padding:7px 10px;background:#f9f9f9;border:1px solid #e0e0e0;"><b>Console</b></td>
                  <td style="padding:7px 10px;border:1px solid #e0e0e0;"><a href="${env.BUILD_URL}console" style="color:${style.color};">View full log</a></td>
                </tr>
              </table>
            </td>
          </tr>

          <!-- Custom body -->
          <tr>
            <td style="padding:20px 28px;">
              ${cfg.body ?: ''}
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background:#f0f0f0;padding:12px 28px;font-size:11px;color:#888;text-align:center;">
              Sent by Jenkins DBmaestro Shared Library &mdash; do not reply
            </td>
          </tr>

        </table>
      </td>
    </tr>
  </table>
</body>
</html>"""

    emailext(
        to      : cfg.to,
        subject : "[DBmaestro][${style.label}] ${cfg.subject}",
        body    : html,
        mimeType: 'text/html'
    )
}
