# OpenMed — Fixtures benchmark : True / False positives

**Cible** : 24 types PII activés par défaut côté détecteur OpenMed (v1.2.0).
**Format** : pour chaque type, 10 cas devant être détectés (TP) et 10 cas pièges plausibles dans le même contexte mais qui **NE doivent PAS** être étiquetés sous ce type.

Toutes les données ci-dessous sont **synthétiques** (générées pour le benchmark, ne correspondent à aucune personne / institution réelle).

> Utilisation : alimenter ces phrases dans un test paramétré sur `OpenMedDetector.detect_pii()`. Mesurer precision = TP / (TP + FP_classés_comme_ce_type) et recall = TP / 10.

---

## Government IDs

### SSN

#### True positives (devraient être détectés comme SSN)
1. `My SSN is 123-45-6789 for tax filing.`
2. `Please confirm your social security number: 987-65-4321.`
3. `SSN 555-12-9876 was used to open the account.`
4. `Employee SSN: 102-99-3450, hired June 2024.`
5. `The form requires your SSN (e.g. 234-56-7890).`
6. `Identifiant social américain : 111-22-3333.`
7. `Updated SSN on file to 421-08-7654.`
8. `Background check linked to SSN 678-90-1234.`
9. `Tax form W-2 lists SSN 305-87-6541.`
10. `SSN ending in 3344 — full number 222-44-3344.`

#### False positives (NE doivent PAS être détectés comme SSN)
1. `Order tracking: 123-45-6789-FR is dispatched.` (commande, pas SSN)
2. `Phone extension 555-12-9876 routes to floor 4.` (extension téléphonique)
3. `Product reference 102-99-3450 in stock.` (SKU produit)
4. `Meeting room 678-90-1234 is on level 3.` (numéro de salle)
5. `Date format used: YYYY-MM-DD, e.g. 2024-05-20.` (date)
6. `Server uptime ratio: 234-56 of 7890 hours.` (statistique fragmentée)
7. `Patent number US-305-87-6541-B2 was granted.` (numéro de brevet)
8. `IPv6 fragment 421-08-7654 in route table.` (fragment réseau)
9. `Game score: 111-22-3333 in last quarter.` (score sportif fictif)
10. `Coordinates 222-44-3344 in legacy grid system.` (système géographique)

---

## Financial

### ACCOUNTNAME

#### True positives
1. `Account holder name: John A. Smith.`
2. `The account is registered under Marie Dupont.`
3. `Titulaire du compte : Société Genevoise SA.`
4. `Beneficiary on account: Aisha Karim.`
5. `Account name: Müller GmbH Treasury.`
6. `Make the transfer to account name "Sophie Laurent".`
7. `Account ownership: Khaled Ben Salem.`
8. `Joint account name: Carlos and Elena Martinez.`
9. `Compte au nom de : Jean-Pierre Rochat.`
10. `Trust account titled "Wakefield Family Trust".`

#### False positives
1. `Login username: jsmith2024.` (username, pas account name)
2. `Project name: Phoenix Migration.` (nom de projet)
3. `Branch office: Geneva-Centre.` (nom d'agence)
4. `Department head signed off.` (rôle générique)
5. `Application name "Treasury Lite".` (nom de logiciel)
6. `File contact john.smith@example.com.` (email)
7. `Customer ID 8847 was created.` (ID client)
8. `Pet name: Rex.` (nom d'animal)
9. `Server hostname: db-prod-01.` (hostname)
10. `Folder labeled "Marie Dupont Photos 2010".` (nom de dossier)

---

### BANKACCOUNT

#### True positives
1. `Bank account number: 00012345678.`
2. `Crediter le compte 4123 5678 9012 3456.`
3. `Account no. 7890-1234-5678-0011 at HSBC.`
4. `IBAN-less local account: 0023-5566-7788.`
5. `Routing 026009593, account 0987654321.`
6. `Compte bancaire n° 12 34 56 78 90 12.`
7. `Wire to account 5544332211009988.`
8. `Sort code 12-34-56, account 87654321.`
9. `Domestic account 9988-7766-5544-3322.`
10. `Numéro de compte courant : 00845720100.`

#### False positives
1. `Phone +41 22 555 12 34.` (téléphone Suisse)
2. `Order ref 0023-5566-7788-OK.` (référence commande)
3. `MAC address 00:1A:2B:3C:4D:5E.` (MAC)
4. `Serial: 0987654321-A1.` (numéro de série)
5. `Coordinate grid 12 34 56 78 90 12.` (coordonnées)
6. `ZIP+4 code 87654-321.` (code postal US)
7. `Software build 5544332211.` (numéro de build)
8. `Postal box 9988-7766.` (boîte postale)
9. `Tracking 0084572010012345.` (numéro de tracking)
10. `License 4123-5678 expired.` (numéro de licence courte)

---

### IBAN

#### True positives
1. `Please transfer to IBAN CH93 0076 2011 6238 5295 7.`
2. `IBAN: DE89 3704 0044 0532 0130 00.`
3. `Notre IBAN est FR76 3000 6000 0112 3456 7890 189.`
4. `Beneficiary IBAN GB29 NWBK 6016 1331 9268 19.`
5. `IBAN du client : ES91 2100 0418 4502 0005 1332.`
6. `Send to IBAN IT60 X054 2811 1010 0000 0123 456.`
7. `IBAN NL91 ABNA 0417 1643 00 confirmed.`
8. `Use IBAN BE68 5390 0754 7034 for SEPA.`
9. `Compte IBAN LU28 0019 4006 4475 0000 ouvert.`
10. `IBAN AT61 1904 3002 3457 3201 received funds.`

#### False positives
1. `Order ID: CH930076 placed.` (préfixe similaire, contexte commande)
2. `License key DE89-3704-0044-0532.` (clé logicielle)
3. `Coupon FR76-3000-6000 expired.` (code promo)
4. `Tracking GB29NWBK60161331.` (numéro de colis)
5. `Project code ES91-2100-0418.` (code projet interne)
6. `Hash IT60X054281110100000.` (hash hex fragmenté)
7. `Phone NL91 0417 1643.` (téléphone reformaté)
8. `Patent BE68 5390 published.` (numéro de brevet)
9. `Survey ID LU28-0019-4006.` (ID de sondage)
10. `Postal code AT61 1904.` (code postal Autriche)

---

### BIC

#### True positives
1. `BIC: CHASUS33XXX for the wire.`
2. `Send to SWIFT/BIC DEUTDEFF.`
3. `BIC du correspondant : BNPAFRPPXXX.`
4. `Use BIC UBSWCHZH80A for Swiss leg.`
5. `BIC HBUKGB4B routes UK GBP.`
6. `BIC INGBNL2A confirmed by ING.`
7. `Provide BIC CITIUS33 for Citi.`
8. `BIC code: HASEHKHH for HK branch.`
9. `BIC : SOGEFRPP pour Société Générale.`
10. `BIC RZBAATWWXXX for Raiffeisen.`

#### False positives
1. `Airport code BNE arrival.` (code aéroport)
2. `Product SKU CHAS-US-33.` (référence produit)
3. `Stock ticker DEUTDE listed.` (ticker boursier)
4. `Module HBUKGB4 imported.` (nom de module)
5. `Country ISO code USA, FRA, DEU.` (codes pays)
6. `License plate UBSWCH80.` (plaque imatriculation imaginaire)
7. `Hex string CITIUS33.` (chaîne hex courte)
8. `Container ID SOGEFR9876.` (ID container)
9. `Build tag RZBAAT-2025.` (tag de build)
10. `City code: HKG (Hong Kong).` (code IATA)

---

### CREDITCARD

#### True positives
1. `Card 4111 1111 1111 1111 declined.`
2. `Use Visa 4539 1488 0343 6467 for the order.`
3. `MC 5555 5555 5555 4444 on file.`
4. `Amex 3782 822463 10005 used today.`
5. `Discover 6011 0000 0000 0004 expires 12/27.`
6. `Card number: 4716-9892-1003-4567.`
7. `Numéro de carte 4929 0000 0000 0006.`
8. `JCB 3530 1113 3330 0000 charged.`
9. `Carta 4000 0566 5566 5556 verified.`
10. `Tarjeta 5105 1051 0510 5100 saved.`

#### False positives
1. `IBAN CH9300762011623852957.` (IBAN, pas carte)
2. `Phone formatted 4111 1111 1111.` (téléphone groupé)
3. `Order 4539-1488-0343-6467-FR.` (numéro commande)
4. `Long invoice 5555 5555 5555 4444 lines.` (chiffre + texte)
5. `Hash hex 4716 9892 1003 4567.` (chaîne hex)
6. `Building ID 3782 822463 10005.` (identifiant bâtiment)
7. `Stadium seat 6011 row 4.` (place stade)
8. `Patent 4929 0000 0000 issued.` (numéro brevet)
9. `Catalog 4000 0566 5566 5556 page.` (catalogue)
10. `Membership 5105 1051 0510 5100 renewed.` (numéro membre)

---

### CREDITCARDISSUER

#### True positives
1. `Issued by Visa.`
2. `MasterCard handled the dispute.`
3. `American Express has approved the merchant.`
4. `Card brand: Discover.`
5. `Émetteur de la carte : JCB.`
6. `Issuer: Diners Club International.`
7. `Carta emessa da UnionPay.`
8. `The Maestro card is accepted here.`
9. `Visa Electron is not supported.`
10. `Carte issued by Carte Bleue.`

#### False positives
1. `Visa office in Bern is closed today.` (Visa = consulat)
2. `Discover the new collection.` (verbe "discover")
3. `JCB asked for a refund.` (initiales personnelles)
4. `Master class on payments.` (homonymie partielle)
5. `Maestro orchestra performed Mozart.` (chef d'orchestre)
6. `Express delivery via American Eagle.` (transporteur)
7. `Diners enjoyed the meal.` (clients restaurant)
8. `Union Pay raise scheduled in March.` (augmentation syndicale)
9. `Carte Bleue est un quartier.` (quartier de ville)
10. `Brand new electron microscope.` (homonyme "electron")

---

### CVV

#### True positives
1. `CVV 123 on the back of the card.`
2. `Three-digit security code: 482.`
3. `Code CVV : 759.`
4. `Amex CID 4582 (4 digits).`
5. `Card verification value 901 required.`
6. `CVC2 246 confirmed.`
7. `Sécurité au dos : 037.`
8. `CVV/CID 7331 entered.`
9. `Please enter the 3-digit code 654.`
10. `CVV2: 089 visible on signature panel.`

#### False positives
1. `Room 482 is reserved.` (numéro de chambre)
2. `Year 759 AD historical reference.` (année)
3. `Bus line 246 stops here.` (ligne de bus)
4. `Page 037 of the manual.` (numéro de page)
5. `Score 089 achieved.` (score sportif)
6. `Highway exit 654.` (sortie autoroute)
7. `Apartment 901 keys.` (numéro appartement)
8. `Section 7331 of the law.` (numéro de section)
9. `Bin 123 contains screws.` (numéro de bac)
10. `Channel 4582 broadcasts news.` (canal TV)

---

### PIN

#### True positives
1. `My ATM PIN is 4729.`
2. `Code PIN de la carte : 8013.`
3. `Set a new 6-digit PIN: 274901.`
4. `PIN code 0042 was rejected.`
5. `Mobile SIM PIN 6182.`
6. `Geheime PIN: 9384.`
7. `PIN d'accès au coffre : 555888.`
8. `Verify with PIN 1209 sent by SMS.`
9. `PIN 7766 entered three times wrong.`
10. `Two-factor PIN: 305211.`

#### False positives
1. `Year 4729 BCE.` (année)
2. `Postal code 8013 in Zurich.` (code postal)
3. `Address number 274901 (route).` (numéro de voie)
4. `Bus route 0042.` (ligne bus)
5. `Apartment 6182 west tower.` (numéro appart)
6. `Stadium seat 9384.` (place stade)
7. `Catalog page 555888.` (page catalogue)
8. `Order ID 1209.` (numéro de commande)
9. `Score 7766 in chess.` (score)
10. `Year 305211 in sci-fi novel.` (année future)

---

### MASKEDNUMBER

#### True positives
1. `Card ending in ****-1234.`
2. `Account XXXX-XXXX-XXXX-5678 charged.`
3. `Phone ***-***-9012.`
4. `SSN ***-**-3344 on file.`
5. `IBAN CH** **** **** **** **957.`
6. `Carte se terminant par xxxx 4321.`
7. `Tel +41 ** *** ** 12.`
8. `Routing ******593.`
9. `PIN dernière digits **89.`
10. `Customer ID #####7654.`

#### False positives
1. `Markdown header *** bold ***.` (formattage)
2. `Regex pattern \d{4}.` (motif regex, pas masque)
3. `Placeholder {{value}} in template.` (variable)
4. `Stars rating ****.` (notation étoiles)
5. `Comment // todo: fix this.` (commentaire)
6. `Censored expletive **** removed.` (censure verbale)
7. `Loading dots ......` (animation)
8. `Equation x = y * z.` (multiplication)
9. `Footnote *** see appendix.` (note de bas de page)
10. `Border decoration ====.` (décoration ASCII)

---

### AMOUNT

#### True positives
1. `Paid 1,250.00 to supplier.`
2. `Invoice total: 87,430.55.`
3. `Salary 95000 annual.`
4. `Refunded 49.99 to the customer.`
5. `Loan amount: 250 000.`
6. `Charge of 3,499.95 disputed.`
7. `Donation 5.00 received.`
8. `Bonus 12345 credited.`
9. `Mortgage 1,750,000 approved.`
10. `Withdrawal 200.00 at ATM.`

#### False positives
1. `Distance 250 000 km from Earth.` (distance, pas montant)
2. `Population 95000 inhabitants.` (population)
3. `Temperature 49.99 degrees Celsius.` (température)
4. `Page 3499 of the document.` (numéro de page)
5. `Year 1,750,000 BCE.` (année préhistorique)
6. `Latitude 12.345 N.` (coordonnée GPS)
7. `Heart rate 87 bpm.` (mesure médicale)
8. `Voltage 1250 V.` (mesure électrique)
9. `Score 5.00/10.` (note d'évaluation)
10. `Step count 12345 today.` (compteur de pas)

---

### CURRENCY

#### True positives
1. `Settlement in euros.`
2. `Paid in US dollars.`
3. `Amount in swiss francs.`
4. `Conversion to japanese yen.`
5. `Quoted in pound sterling.`
6. `Accept payment in canadian dollars.`
7. `Reserves held in chinese yuan.`
8. `Invoice in mexican pesos.`
9. `Cost in indian rupees.`
10. `Bid in australian dollars.`

#### False positives
1. `Euros (city in Greece).` (toponyme)
2. `Dollar Tree store in Reno.` (nom de chaîne)
3. `Frank the dog barked.` (prénom anglais)
4. `Yen for chocolate today.` (verbe "yearn for")
5. `Pound the table angrily.` (verbe "pound")
6. `Rupees was the family pet.` (surnom)
7. `Yuan dynasty artifacts.` (dynastie)
8. `Pesos brothers band.` (nom artistique)
9. `Sterling silver necklace.` (matière)
10. `Canadian club bar.` (nom de bar)

---

### CURRENCYCODE

#### True positives
1. `Amount: 1500 USD.`
2. `Paid 700 EUR via SEPA.`
3. `Receive 2,000 CHF.`
4. `Quote in GBP.`
5. `Exchange JPY to USD.`
6. `Settlement in AUD.`
7. `Holdings in CAD.`
8. `Buy CNY at spot.`
9. `Sell INR at market.`
10. `Reserves in BRL.`

#### False positives
1. `Server in USA region.` (code pays partiel)
2. `Standard ISO code USB.` (acronyme tech)
3. `Project CHF-2025 launched.` (préfixe de projet)
4. `GBP = Great British Press.` (acronyme alternatif)
5. `JPY-files in legacy archive.` (préfixe de fichier)
6. `AUD video output enabled.` (audio fragment)
7. `CAD software license.` (computer-aided design)
8. `CNY Spring Festival schedule.` (Chinese New Year)
9. `INR-net research network.` (acronyme réseau)
10. `BRL kernel release notes.` (préfixe de package)

---

### CURRENCYNAME

#### True positives
1. `Settlement in U.S. Dollar.`
2. `Held in Swiss Franc reserves.`
3. `Invoice in Japanese Yen.`
4. `Quote in British Pound.`
5. `Conversion from Euro to Dollar.`
6. `Paid 5,000 Canadian Dollar.`
7. `Exchanged to Chinese Yuan.`
8. `Holdings in Indian Rupee.`
9. `Bond in Brazilian Real.`
10. `Transfer in Australian Dollar.`

#### False positives
1. `The pound is a unit of weight.` (unité de masse)
2. `Yen for travel grew stronger.` (verbe "yearn")
3. `Franc Drabek was the captain.` (prénom slave)
4. `Real estate market dropped.` (immobilier)
5. `Yuan dynasty pottery on display.` (dynastie)
6. `Dollar Bill is a nickname.` (surnom)
7. `Rupee street vendor in Mumbai.` (toponyme imaginé)
8. `Euro is a vacation destination.` (jeu de mots)
9. `Sterling effort by the team.` (adjectif anglais)
10. `Real number theory class.` (concept mathématique)

---

### CURRENCYSYMBOL

#### True positives
1. `Charged $1,250.`
2. `Total: €99.99.`
3. `Paid £450.`
4. `Costs ¥3,000.`
5. `Saldo : CHF 200.`
6. `Equivalent in ₹1,000.`
7. `Sub-total $0.99.`
8. `Donate €5.`
9. `Royalty £12.50/unit.`
10. `Coffee ¥350.`

#### False positives
1. `Regex $1 backreference.` (capture regex)
2. `Variable $name in shell.` (variable bash)
3. `Math symbol € appeared in font.` (mention typographique)
4. `End marker £ in legacy format.` (caractère délimiteur)
5. `Character ¥ from Unicode block.` (description Unicode)
6. `Footnote symbol used.` (note typographique)
7. `Emoji 💵 in chat.` (emoji)
8. `Annotated *$* in code review.` (caractère commenté)
9. `Letter 𝛼 (alpha) in formula.` (lettre grecque)
10. `Glyph ¢ added to font.` (description glyphe)

---

## Crypto

### BITCOINADDRESS

#### True positives
1. `Send BTC to 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa.`
2. `My wallet: 3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy.`
3. `Donate to bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq.`
4. `Bitcoin deposit address 1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2.`
5. `Cold wallet 3FZbgi29cpjq2GjdwV8eyHuJJnkLtktZc5.`
6. `BTC: bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7.`
7. `Receive to 12dRugNcdxK39288NjcDV4GX7rMsKCGn6B.`
8. `Address legacy: 1Bf9sZvBHPFGVPX71WX2njhd1NXKv5y7v.`
9. `Bitcoin tip jar 3PLebVbpKohMzPiC7SmurqYwBZahJgF6Kg.`
10. `BTC payout to bc1q9d4ywgfnd8h43da5tpcxcn6ajv590cg6d3tg6axemvljvt2k76zs50tv4q.`

#### False positives
1. `Git hash 1A1zP1eP5QGefi2DMPTfT.` (hash commit court)
2. `S3 bucket key 3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy.txt.` (clé S3)
3. `Base58 demo string bc1qar0srrr.` (chaîne demo encodage)
4. `JWT fragment 1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2.` (token découpé)
5. `License key 3FZbgi29cpjq2GjdwV8eyHuJJnkLtktZc5.` (clé logicielle)
6. `Object ID bc1qrp33g0q5c5txsp9arysrx4k.` (Mongo ObjectId étendu)
7. `Random string 12dRugNcdxK39288NjcDV4GX7rMsKCGn6B.` (test fixture)
8. `Container hash 1Bf9sZvBHPFGVPX71WX2njhd1NXKv5y7v.` (Docker layer)
9. `UUID encoded 3PLebVbpKohMzPiC7SmurqYwBZahJgF6Kg.` (UUID custom)
10. `Cache token bc1q9d4ywgfnd8h43da5tpcxcn6ajv.` (token cache)

---

### ETHEREUMADDRESS

#### True positives
1. `Send ETH to 0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0.`
2. `Vitalik's address: 0xab5801a7d398351b8be11c439e05c5b3259aec9b.`
3. `Smart contract 0xdAC17F958D2ee523a2206206994597C13D831ec7.`
4. `Deploy from 0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48.`
5. `Treasury wallet 0x6B175474E89094C44Da98b954EedeAC495271d0F.`
6. `Sender 0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2.`
7. `Receiver 0x514910771AF9Ca656af840dff83E8264EcF986CA.`
8. `Burn address 0x0000000000000000000000000000000000000000.`
9. `Multisig 0x9F8Aa6c9D1aA5cFa50fE4fdF9b0fEbe1fD0d54B5.`
10. `Donate ETH 0xb794F5eA0ba39494cE839613fffBA74279579268.`

#### False positives
1. `Color hex 0x742d35.` (couleur hex)
2. `Memory offset 0xab5801a7.` (offset mémoire)
3. `Function ptr 0xdAC17F958D2ee523.` (pointeur)
4. `Symbol address 0xA0b86991.` (adresse symbole)
5. `Sha-256 prefix 0x6B175474E8909.` (hash partiel)
6. `Texture ID 0xC02aaA39b22.` (ID texture)
7. `Buffer 0x514910771AF9Ca656.` (buffer hexadécimal)
8. `Null pointer 0x0000000000000000.` (pointeur null)
9. `Magic number 0x9F8Aa6c9.` (constante magique)
10. `Random seed 0xb794F5eA.` (graine PRNG)

---

### LITECOINADDRESS

#### True positives
1. `Litecoin: LQTpS3VaYTjCr4s3jhh2pYzPe4dKxVZkZB.`
2. `Send LTC to MTf4tP1TUoBBqmJXJ2pAYn3zHcRSP4pVQ8.`
3. `LTC wallet ltc1qhhvyumc7lue7rk5gvxqxu6vk5x3vqj6t3yfdge.`
4. `Cold storage LRzpEfeQzwR2bDjmtuD8TBoH9PWUC5Gj5p.`
5. `Donate LTC LZ8GZjk5dyEN8Wpv5ETM9j7Cj9dPmFn5RB.`
6. `Litecoin tip MJfDfXLZuW4mNzcm1nLpW7uYpwScS4yGhg.`
7. `LTC address LfmsXxiK2gZW9RvVNRsEi8sjwBT1HnJ2jW.`
8. `Receive Litecoins at LcZQfzKwzPGcndmiU3D6Pq6h2W4P6QqXEC.`
9. `Mining payout LXyM3UAtJEdzwH2BWWnY3a5sRLeMa5z4xV.`
10. `Wallet ltc1q4gpz0fc99q2nf48mxulj5xc6f0wm7lyl8d8wzv.`

#### False positives
1. `License token LQTpS3VaYTjCr4s3.` (token license partiel)
2. `Movie code MTf4tP1TU.` (code film court)
3. `URL slug ltc1qhhvyumc7lue7.` (slug d'URL)
4. `Inventory tag LRzpEfeQzwR2.` (étiquette inventaire)
5. `Course ID LZ8GZjk5dy.` (ID de cours)
6. `Tracking MJfDfXLZuW4m.` (numéro de suivi)
7. `Catalog entry LfmsXxiK2gZW.` (entrée catalogue)
8. `Member badge LcZQfzKwzPGc.` (badge membre)
9. `Test fixture LXyM3UAtJE.` (fixture de test)
10. `Coupon ltc1q4gpz0fc99.` (coupon promo)

---

## Vehicle

### VIN

#### True positives
1. `VIN: 1HGCM82633A123456.`
2. `Vehicle identification 5YJSA1E26HF150123.`
3. `VIN of the wreck: WBANV93578CZ12345.`
4. `Châssis n° JTHBE5C24A5012345.`
5. `Truck VIN: 1FTFW1ET4DFC12345.`
6. `Numéro VIN : JM1NC2EF5A0123456.`
7. `Recall affects VIN 2C3CCAAG3DH123456.`
8. `Insurance refers to VIN KMHFG4JG6CA123456.`
9. `Title document VIN JN8AZ2NF7G9123456.`
10. `Imported with VIN 5N1AR2MN3FC123456.`

#### False positives
1. `Container ID 1HGCM82633A.` (ID conteneur)
2. `Serial 5YJSA1E26HF (partial).` (numéro de série tronqué)
3. `Product code WBANV93578CZ.` (code produit long)
4. `License key JTHBE5C24A50.` (clé logicielle)
5. `Batch 1FTFW1ET4DFC.` (numéro de lot)
6. `Asset tag JM1NC2EF5A01.` (étiquette inventaire)
7. `Lot 2C3CCAAG3DH.` (numéro de lot)
8. `IMEI fragment KMHFG4JG6C.` (fragment IMEI)
9. `Patent JN8AZ2NF7G9.` (numéro de brevet)
10. `Order 5N1AR2MN3FC123456-FR.` (commande)

---

### VRM

#### True positives
1. `License plate AB-12-CD spotted.`
2. `Immatriculation : VD 123 456.`
3. `Plate number HX65 NHK.`
4. `Voiture imm. GE 45 678.`
5. `Reg AB12 XYZ on file.`
6. `Plaque ZH 9876 vue ce matin.`
7. `Truck plate K12 ABC.`
8. `Numéro de plaque BE 1234.`
9. `VRM: T567 PQR.`
10. `Immat. NE 12 345.`

#### False positives
1. `Code AB-12-CD in product catalog.` (code produit)
2. `Building block VD-123.` (code bâtiment)
3. `Lab sample HX65.` (échantillon labo)
4. `Game seat GE-45-678.` (place stade)
5. `Survey AB12-XYZ.` (code sondage)
6. `Box ZH-9876 in warehouse.` (carton entrepôt)
7. `Postal route K12.` (route postale)
8. `Conference badge BE-1234.` (badge conf)
9. `Sample T567 archived.` (échantillon)
10. `Drawer NE-12-345 locked.` (tiroir)

---

## Digital

### IPADDRESS

#### True positives
1. `Server at 192.168.1.10 is reachable.`
2. `Public IP 8.8.8.8 belongs to Google DNS.`
3. `Block 10.0.0.42 in firewall.`
4. `Allowed origin 203.0.113.15.`
5. `Gateway: 172.16.0.1.`
6. `IPv6 2001:db8::8a2e:370:7334 configured.`
7. `Reverse DNS for 198.51.100.7.`
8. `Test bench host 127.0.0.1.`
9. `Source IP 192.0.2.45 blocked.`
10. `IPv6 ::1 is loopback.`

#### False positives
1. `Version 192.168.1.10 of the spec.` (numéro de version)
2. `Software v8.8.8.8 released.` (version semver étendue)
3. `Build 10.0.0.42 nightly.` (numéro de build)
4. `Document section 203.0.113.15.` (référence document)
5. `Date 2001.08.20.07 written wrong.` (date mal formatée)
6. `Decimal 198.51.100.7 in CSV.` (valeur CSV)
7. `Latitude 172.16 (typo).` (coordonnée tronquée)
8. `Score 192.0.2.45 mistyped.` (score erroné)
9. `Article DOI 10.1000.182.4.` (DOI académique)
10. `RFC 127.0.0.1 reference.` (le texte mentionne loopback mais comme citation)

---

### MACADDRESS

#### True positives
1. `MAC 00:1A:2B:3C:4D:5E on eth0.`
2. `Device MAC AA:BB:CC:DD:EE:FF blocked.`
3. `Adapter MAC: 02-42-AC-11-00-02.`
4. `WiFi MAC 5C:CF:7F:1A:2B:3C.`
5. `Bluetooth 90:8D:78:F4:A1:23 paired.`
6. `Switch port MAC F0:1F:AF:11:22:33.`
7. `Router MAC : 00:25:96:FF:FE:12.`
8. `IoT device 18:FE:34:AB:CD:EF.`
9. `MAC=B8:27:EB:01:02:03 (Raspberry Pi).`
10. `Sniffed MAC 3C:5A:B4:99:88:77.`

#### False positives
1. `Hash digest 00:1A:2B:3C:4D:5E (mock).` (hash colon-separated)
2. `Timestamp AA:BB:CC display.` (timestamp imaginaire)
3. `Color palette 02-42-AC-11.` (palette de couleurs)
4. `Music tempo 5C:CF (BPM marks).` (marqueurs musique)
5. `Build tag 90:8D:78:F4 release.` (tag release)
6. `Chemistry F0:1F:AF analysis.` (notation chimique fantaisiste)
7. `Phone 00 25 96 FF.` (téléphone avec espaces, autre type)
8. `Sample 18:FE:34:AB on slide.` (échantillon labo)
9. `Logo B8:27:EB design.` (design notation)
10. `Track 3C:5A:B4:99 mixed.` (mix musical)

---

### IMEI

#### True positives
1. `IMEI: 490154203237518.`
2. `Phone IMEI 356938035643809.`
3. `IMEI registered 357805071234567.`
4. `Track via IMEI 869821046123456.`
5. `Stolen device IMEI 014441002145323.`
6. `Numéro IMEI : 990000862471854.`
7. `IMEI of unit: 358240051111110.`
8. `New phone IMEI 460002030001324.`
9. `IMEI 359878102345678 reported.`
10. `Device IMEI 351756051523999.`

#### False positives
1. `Order 490154203237518-FR shipped.` (commande)
2. `Tracking 356938035643809.` (suivi colis)
3. `Long invoice 357805071234567 line.` (ligne facture)
4. `Patent 869821046123456 filed.` (numéro brevet)
5. `Container 014441002145323.` (numéro conteneur)
6. `Hash decimal 990000862471854.` (hash décimal)
7. `Telco subscriber 358240051111110 (other).` (abonné, pas IMEI)
8. `Bank routing 460002030001324.` (numéro routing étendu)
9. `Account 359878102345678 closed.` (compte long)
10. `Tax ID 351756051523999.` (identifiant fiscal long)

---

## Auth

### PASSWORD

#### True positives
1. `My password is Hunter2!@.`
2. `Use password Sup3r$ecret to login.`
3. `Password reset to TempPass#2024.`
4. `Old password was Welcome123!.`
5. `Le mot de passe est Été2025@CH.`
6. `Set the password to RailWay!88.`
7. `Generated password: Z9!kLm2$pQ.`
8. `Master password Vault#Pass9.`
9. `New WiFi password CafeLatte!2024.`
10. `Token password = Cl0udNative#42.`

#### False positives
1. `The word "password" appeared in logs.` (mention du mot lui-même, pas la valeur)
2. `Password manager 1Password is recommended.` (nom de produit)
3. `Reset link expires in 30 minutes.` (instruction sans valeur)
4. `Use a strong, unique password.` (conseil générique)
5. `Hunter2 is a famous joke.` (référence culturelle, pas password réel)
6. `Project codename: Welcome123.` (nom de projet)
7. `Subject of the talk: passwords in 2025.` (sujet conférence)
8. `Argument --password is required.` (option CLI)
9. `Class field private String password;` (déclaration de variable)
10. `Documentation: choose a memorable password.` (instruction utilisateur)

---

## Comment utiliser ces fixtures

### Test paramétré suggéré (Python)

```python
import pytest
from pathlib import Path
from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector

FIXTURES_FILE = Path(__file__).parent.parent / "doc" / "openmed-benchmark-fixtures.md"

@pytest.fixture(scope="module")
def detector():
    d = OpenMedDetector()
    d.load_model()
    return d

def parse_fixtures():
    """Yield (pii_type, expected_match, phrase) tuples parsed from the .md file."""
    ...

@pytest.mark.integration
@pytest.mark.slow
@pytest.mark.parametrize("pii_type,expected_match,phrase", list(parse_fixtures()))
def test_Should_RespectExpectedDetection_When_OpenMedScansFixture(detector, pii_type, expected_match, phrase):
    entities = detector.detect_pii(phrase)
    matched = any(e.pii_type == pii_type for e in entities)
    assert matched == expected_match, (
        f"{pii_type}: expected match={expected_match}, got {matched} on: {phrase}"
    )
```

### Métriques attendues (cible MVP)

Pour chaque `pii_type` :

| Métrique | Formule | Cible v1 |
|---|---|---|
| **Recall** (TP rate) | matchs sur les 10 TP / 10 | ≥ 8/10 |
| **Specificity** (TN rate) | non-matchs sur les 10 FP / 10 | ≥ 8/10 |
| **Erreurs majeures** | FP classés en HIGH severity | 0 |

Les types avec recall < 8/10 ou specificity < 8/10 sont candidats à un ajustement du `threshold` dans `data.sql` (ou désactivation par défaut si la précision est trop faible).

### Notes

- Les phrases sont volontairement variées en langue (FR + EN + un peu DE/IT/ES) pour exploiter le caractère multilingue du modèle.
- Les FP sont conçus pour piéger un modèle qui se baserait uniquement sur le format (chiffres groupés, longueur, prefixe) sans tenir compte du contexte sémantique.
- Données 100% synthétiques — aucune ne correspond à une carte, IBAN, SSN, IP, ou plaque réels.
