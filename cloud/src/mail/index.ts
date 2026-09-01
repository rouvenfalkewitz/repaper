import { createMailProvider } from "./provider.js";
import { inviteMail, registerMail, resetMail } from "./templates.js";

const provider = createMailProvider();

/* true when mails actually leave the machine — the UI shows invite links for
   hand-delivery when this is false */
export const mailEnabled = provider.id !== "console";

export const sendInviteMail = (to: string, link: string, orgName: string, inviterName: string) =>
  provider.send({ to, ...inviteMail(link, orgName, inviterName) });
export const sendRegisterMail = (to: string, link: string, orgName: string) =>
  provider.send({ to, ...registerMail(link, orgName) });
export const sendResetMail = (to: string, link: string) =>
  provider.send({ to, ...resetMail(link) });
