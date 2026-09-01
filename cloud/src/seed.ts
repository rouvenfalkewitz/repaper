/* Closed pilot: there is no signup. Accounts are created here, on the box.
     npm run seed -- --org "RePaper" --email you@example.com --password "..." [--name "You"]  */
import { createOrg, createUser, getOrgByName, getUserByEmail } from "./db.js";
import { hashPassword } from "./auth.js";

const args = new Map<string, string>();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i].replace(/^--/, ""), process.argv[i + 1] ?? "");

const orgName = args.get("org"), email = args.get("email"), password = args.get("password");
if (!orgName || !email || !password) {
  console.error('usage: npm run seed -- --org "RePaper" --email you@example.com --password "..." [--name "You"]');
  process.exit(1);
}
if (password.length < 10) { console.error("pick a password of at least 10 characters"); process.exit(1); }
if (getUserByEmail(email)) { console.error(`${email} already exists`); process.exit(1); }

const org = getOrgByName(orgName) ?? createOrg(orgName);
createUser(org.id, email, args.get("name") ?? "", hashPassword(password), "admin");
console.log(`created admin ${email} in org "${org.name}"`);
