"""
OwnClaw Core Skill: send_email
Sends an email via SMTP with auto-detection of SSL/STARTTLS.

Credentials (injected as env vars):
  SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS

Parameters:
  to       - recipient email address (required)
  subject  - email subject (required)
  body     - email body text (required)
  html     - if "true", send body as HTML (optional, default false)
  cc       - comma-separated CC addresses (optional)
  bcc      - comma-separated BCC addresses (optional)
"""
import json
import sys
import os
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart


def emit(type_, **kwargs):
    print(json.dumps({"type": type_, **kwargs}), flush=True)


def main():
    params = json.load(sys.stdin)

    to = params.get("to", "").strip()
    subject = params.get("subject", "").strip()
    body = params.get("body", "").strip()
    is_html = str(params.get("html", "false")).lower() == "true"
    cc = params.get("cc", "").strip()
    bcc = params.get("bcc", "").strip()

    if not to:
        emit("result", status="error", output={"error": "Missing required parameter: to"})
        return
    if not subject:
        emit("result", status="error", output={"error": "Missing required parameter: subject"})
        return
    if not body:
        emit("result", status="error", output={"error": "Missing required parameter: body"})
        return

    # Load SMTP credentials from environment
    smtp_host = os.environ.get("SMTP_HOST", "").strip()
    smtp_port = int(os.environ.get("SMTP_PORT", "587"))
    smtp_user = os.environ.get("SMTP_USER", "").strip()
    smtp_pass = os.environ.get("SMTP_PASS", "")

    if not smtp_host:
        emit("result", status="error",
             output={"error": "SMTP_HOST not configured. Set credentials with /cred set SMTP_HOST <value>"})
        return
    if not smtp_user:
        emit("result", status="error",
             output={"error": "SMTP_USER not configured. Set credentials with /cred set SMTP_USER <value>"})
        return

    # Build email
    msg = MIMEMultipart("alternative") if is_html else None
    if is_html:
        msg.attach(MIMEText(body, "html", "utf-8"))
    else:
        msg = MIMEText(body, "plain", "utf-8")

    msg["Subject"] = subject
    msg["From"] = smtp_user
    msg["To"] = to
    if cc:
        msg["Cc"] = cc
    recipients = [addr.strip() for addr in to.split(",")]
    if cc:
        recipients.extend(addr.strip() for addr in cc.split(","))
    if bcc:
        recipients.extend(addr.strip() for addr in bcc.split(","))

    emit("progress", message=f"Connecting to {smtp_host}:{smtp_port}...")

    try:
        if smtp_port == 465:
            # SSL
            server = smtplib.SMTP_SSL(smtp_host, smtp_port, timeout=30)
        else:
            # STARTTLS
            server = smtplib.SMTP(smtp_host, smtp_port, timeout=30)
            server.ehlo()
            server.starttls()
            server.ehlo()

        if smtp_user and smtp_pass:
            server.login(smtp_user, smtp_pass)

        server.sendmail(smtp_user, recipients, msg.as_string())
        server.quit()

        emit("result", status="success", output={
            "message": f"Email sent successfully to {to}",
            "to": to,
            "subject": subject,
            "cc": cc if cc else None,
            "bcc": bcc if bcc else None
        })

    except smtplib.SMTPAuthenticationError as e:
        emit("result", status="error", output={
            "error": f"SMTP authentication failed: {e}",
            "hint": "Check SMTP_USER and SMTP_PASS credentials"
        })
    except smtplib.SMTPConnectError as e:
        emit("result", status="error", output={
            "error": f"Cannot connect to SMTP server: {e}",
            "hint": f"Check SMTP_HOST ({smtp_host}) and SMTP_PORT ({smtp_port})"
        })
    except Exception as e:
        emit("result", status="error", output={"error": str(e)})


if __name__ == "__main__":
    main()
