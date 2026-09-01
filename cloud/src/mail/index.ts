import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createMailProvider, type InlineImage } from "./provider.js";
import { inviteMail, registerMail, resetMail } from "./templates.js";

const provider = createMailProvider();

/* the brand wordmark (light variant — mails render on the Paper palette),
   shipped as an inline attachment so it shows even where remote images don't */
const wordmark: InlineImage = {
  cid: "repaper-wordmark",
  filename: "repaper.png",
  content: readFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", "..", "assets", "lockup-light.png")),
  contentType: "image/png",
};

/* true when mails actually leave the machine — the UI shows invite links for
   hand-delivery when this is false */
export const mailEnabled = provider.id !== "console";

export const sendInviteMail = (to: string, link: string, orgName: string, inviterName: string) =>
  provider.send({ to, ...inviteMail(link, orgName, inviterName), inline: [wordmark] });
export const sendRegisterMail = (to: string, link: string) =>
  provider.send({ to, ...registerMail(link), inline: [wordmark] });
export const sendResetMail = (to: string, link: string) =>
  provider.send({ to, ...resetMail(link), inline: [wordmark] });
