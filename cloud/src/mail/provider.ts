/* The mail transport is pluggable, same idea as SheetTransport on the Dock:
   templates build a MailMessage, a MailProvider delivers it. SMTP (the IONOS
   mailbox) is the first provider; when volume outgrows a mailbox, a Postmark /
   SES / Resend provider is one new class here plus a MAIL_TRANSPORT value —
   nothing above this file changes. */
import { createTransport, type Transporter } from "nodemailer";

export type InlineImage = { cid: string; filename: string; content: Buffer; contentType: string };
export type MailMessage = { to: string; subject: string; text: string; html?: string; inline?: InlineImage[] };

export interface MailProvider {
  readonly id: string;
  send(msg: MailMessage): Promise<void>;
}

/* Default: deliver nothing, log enough to hand a link over by hand. */
export class ConsoleMailProvider implements MailProvider {
  readonly id = "console";
  async send(m: MailMessage): Promise<void> {
    console.log(`MAIL (console transport) to=${m.to} subject=${JSON.stringify(m.subject)}\n${m.text}`);
  }
}

export class SmtpMailProvider implements MailProvider {
  readonly id = "smtp";
  private transporter: Transporter;
  private from: string;
  constructor() {
    const port = Number(process.env.SMTP_PORT || 587);
    this.transporter = createTransport({
      host: process.env.SMTP_HOST || "smtp.ionos.de",
      port,
      secure: port === 465,
      auth: { user: process.env.SMTP_USER || "", pass: process.env.SMTP_PASS || "" },
    });
    this.from = process.env.MAIL_FROM || "RePaper Cloud <repaper@schisch.net>";
  }
  async send(m: MailMessage): Promise<void> {
    await this.transporter.sendMail({
      from: this.from, to: m.to, subject: m.subject, text: m.text, html: m.html,
      attachments: m.inline?.map((i) => ({ cid: i.cid, filename: i.filename, content: i.content, contentType: i.contentType, contentDisposition: "inline" as const })),
    });
  }
}

export const createMailProvider = (): MailProvider => {
  const mode = process.env.MAIL_TRANSPORT || "console";
  switch (mode) {
    case "smtp": return new SmtpMailProvider();
    case "console": return new ConsoleMailProvider();
    // future: case "postmark": ... case "ses": ... — implement MailProvider above
    default:
      console.warn(`unknown MAIL_TRANSPORT "${mode}" — falling back to console`);
      return new ConsoleMailProvider();
  }
};
