/* Outgoing mail. MAIL_TRANSPORT=smtp sends via the configured SMTP account
   (IONOS: smtp.ionos.de:587, the repaper@schisch.net mailbox); the default
   "console" transport logs instead of sending, so invites work before any
   mailbox exists — the console also hands the admin the link to pass on. */
import { createTransport, type Transporter } from "nodemailer";

const mode = process.env.MAIL_TRANSPORT || "console";
export const mailEnabled = mode === "smtp";

let smtp: Transporter | null = null;
if (mailEnabled) {
  smtp = createTransport({
    host: process.env.SMTP_HOST || "smtp.ionos.de",
    port: Number(process.env.SMTP_PORT || 587),
    secure: Number(process.env.SMTP_PORT || 587) === 465,
    auth: { user: process.env.SMTP_USER || "", pass: process.env.SMTP_PASS || "" },
  });
}

const FROM = process.env.MAIL_FROM || "RePaper Cloud <repaper@schisch.net>";

export const sendMail = async (to: string, subject: string, text: string): Promise<void> => {
  if (!smtp) {
    console.log(`MAIL (console transport) to=${to} subject=${JSON.stringify(subject)}\n${text}`);
    return;
  }
  await smtp.sendMail({ from: FROM, to, subject, text });
};

export const sendRegisterMail = (to: string, link: string, orgName: string) =>
  sendMail(
    to,
    "Confirm your RePaper Cloud registration",
    `You (or someone using your address) registered for "${orgName}" on RePaper Cloud —\n` +
      `the console for RePaper printers and e-paper sheets.\n\n` +
      `Confirm your email and pick a password here (the link is valid for 14 days):\n\n  ${link}\n\n` +
      `If this wasn't you, ignore this mail; no account was created.\n\n` +
      `— RePaper Cloud`
  );

export const sendInviteMail = (to: string, link: string, orgName: string, inviterName: string) =>
  sendMail(
    to,
    `${inviterName} invited you to ${orgName} on RePaper Cloud`,
    `${inviterName} invited you to the "${orgName}" fleet on RePaper Cloud —\n` +
      `the console for RePaper printers and e-paper sheets.\n\n` +
      `Create your account here (the link is valid for 14 days):\n\n  ${link}\n\n` +
      `If you weren't expecting this, you can ignore this mail; nothing was created for you.\n\n` +
      `— RePaper Cloud`
  );
