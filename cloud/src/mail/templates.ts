/* Branded transactional mails. Email clients get the Paper (light) palette from
   brand/tokens.css — dark backgrounds are unreliable in mail clients, and paper
   is the right metaphor anyway. Everything inline-styled, table layout, with a
   plain-text part that stands on its own. */

const C = {
  paper: "#EFF1EE",     // ground
  sheet: "#FAFBF9",     // card
  ink: "#131614",       // text
  graphite: "#454B47",  // secondary text
  ash: "#8B928E",       // tertiary
  line: "#D6DAD6",      // borders
  green: "#14C48E",     // --re-500: fills on light
  carbon: "#0C100F",    // on-accent
};
const FONT = "'Helvetica Neue',Helvetica,Arial,sans-serif";
const MONO = "'SF Mono',Menlo,Consolas,monospace";

export type RenderedMail = { subject: string; text: string; html: string };

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]!));

type Layout = {
  preheader: string;
  heading: string;
  paragraphs: string[];        // may contain <b> — callers escape dynamic values
  button?: { label: string; url: string };
  note?: string;               // muted line under the button (validity, "wasn't you?")
  footer: string;
};

const layout = (l: Layout): string => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="color-scheme" content="light"><title>${esc(l.heading)}</title></head>
<body style="margin:0;padding:0;background:${C.paper};">
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">${esc(l.preheader)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${C.paper};">
<tr><td align="center" style="padding:40px 16px;">
  <table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;max-width:460px;">
    <tr><td align="left" style="padding:0 6px 18px;">
      <img src="cid:repaper-wordmark" width="85" height="30" alt="REPAPER" style="display:block;border:0;outline:none;font:800 17px ${FONT};color:${C.ink};">
    </td></tr>
    <tr><td style="background:${C.sheet};border:1px solid ${C.line};border-radius:16px;padding:32px;">
      <h1 style="margin:0 0 14px;font:700 20px/26px ${FONT};letter-spacing:-0.3px;color:${C.ink};">${esc(l.heading)}</h1>
      ${l.paragraphs.map((p) => `<p style="margin:0 0 16px;font:400 15px/23px ${FONT};color:${C.graphite};">${p}</p>`).join("\n      ")}
      ${l.button ? `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:8px 0 4px;"><tr>
        <td style="border-radius:8px;background:${C.green};">
          <a href="${esc(l.button.url)}" style="display:inline-block;padding:13px 26px;font:600 15px ${FONT};color:${C.carbon};text-decoration:none;border-radius:8px;">${esc(l.button.label)}</a>
        </td></tr></table>
      <p style="margin:16px 0 0;font:400 12px/18px ${MONO};color:${C.ash};word-break:break-all;">Or paste this link: ${esc(l.button.url)}</p>` : ""}
      ${l.note ? `<p style="margin:18px 0 0;padding-top:16px;border-top:1px solid ${C.line};font:400 13px/19px ${FONT};color:${C.ash};">${l.note}</p>` : ""}
    </td></tr>
    <tr><td align="left" style="padding:16px 6px 0;font:400 12px/18px ${FONT};color:${C.ash};">${l.footer}</td></tr>
  </table>
</td></tr></table>
</body></html>`;

export const inviteMail = (link: string, orgName: string, inviterName: string): RenderedMail => ({
  subject: `${inviterName} invited you to ${orgName} on RePaper Cloud`,
  text:
    `${inviterName} invited you to the "${orgName}" fleet on RePaper Cloud —\n` +
    `the console for RePaper printers and e-paper sheets.\n\n` +
    `Create your account here (the link is valid for 14 days):\n\n  ${link}\n\n` +
    `If you weren't expecting this, you can ignore this mail; nothing was created for you.\n\n` +
    `— RePaper Cloud`,
  html: layout({
    preheader: `Join the ${orgName} fleet — pick your password and you're in.`,
    heading: "You're invited",
    paragraphs: [
      `<b>${esc(inviterName)}</b> invited you to <b>${esc(orgName)}</b> on RePaper Cloud — the console for RePaper printers and their e-paper sheets.`,
      `Pick your own password and you're in:`,
    ],
    button: { label: "Create your account", url: link },
    note: "The invitation is valid for 14 days and works once. Not expecting this? Ignore this mail — nothing was created for you.",
    footer: "RePaper Cloud · print on paper that never needs ink",
  }),
});

export const registerMail = (link: string, orgName: string): RenderedMail => ({
  subject: "Confirm your RePaper Cloud registration",
  text:
    `You (or someone using your address) registered for "${orgName}" on RePaper Cloud —\n` +
    `the console for RePaper printers and e-paper sheets.\n\n` +
    `Confirm your email and pick a password here (the link is valid for 14 days):\n\n  ${link}\n\n` +
    `If this wasn't you, ignore this mail; no account was created.\n\n` +
    `— RePaper Cloud`,
  html: layout({
    preheader: "One click to confirm your email, then pick a password.",
    heading: "Confirm your registration",
    paragraphs: [
      `You registered for <b>${esc(orgName)}</b> on RePaper Cloud. Confirm your email and pick a password — that's all:`,
    ],
    button: { label: "Confirm & choose a password", url: link },
    note: "The link is valid for 14 days and works once. Wasn't you? Ignore this mail — no account was created.",
    footer: "RePaper Cloud · print on paper that never needs ink",
  }),
});

export const resetMail = (link: string): RenderedMail => ({
  subject: "Reset your RePaper Cloud password",
  text:
    `Someone (hopefully you) asked to reset the password for this account on RePaper Cloud.\n\n` +
    `Set a new one here (the link works once and expires in 2 hours):\n\n  ${link}\n\n` +
    `If this wasn't you, ignore this mail — your password stays as it is.\n\n` +
    `— RePaper Cloud`,
  html: layout({
    preheader: "Set a new password — the link works once and expires in 2 hours.",
    heading: "Reset your password",
    paragraphs: [`Someone — hopefully you — asked to reset your RePaper Cloud password. Set a new one here:`],
    button: { label: "Set a new password", url: link },
    note: "The link works once and expires in 2 hours. Wasn't you? Ignore this mail — your password stays as it is.",
    footer: "RePaper Cloud · print on paper that never needs ink",
  }),
});
