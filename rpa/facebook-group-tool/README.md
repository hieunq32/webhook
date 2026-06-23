# Facebook Group RPA Tool

Tool nay expose endpoint `/tools/invoke` tuong thich voi Spring Boot module `facebook.group-posting`.
No dung Playwright de login Facebook mot lan, luu session cookie, sau do tu dong dang bai vao Facebook Group.

## 1. Cai dat trong WSL Ubuntu

```bash
cd /mnt/c/Users/sagit/OneDrive/Desktop/Source/webhook/rpa/facebook-group-tool
npm install
npm run install:browsers
```

Neu Playwright bao thieu Linux dependencies, chay:

```bash
npx playwright install-deps chromium
```

Khuyen nghi trong project nay: chay script setup tu root project de tranh loi `EPERM chmod` khi npm install tren OneDrive/Windows mount.

```bash
cd /mnt/c/Users/sagit/OneDrive/Desktop/Source/webhook
bash ./scripts/setup-facebook-group-rpa-wsl.sh
```

Script se copy tool sang:

```text
~/.recruitment-rpa/facebook-group-tool
```

## 2. Login Facebook va luu session

```bash
cd /mnt/c/Users/sagit/OneDrive/Desktop/Source/webhook
bash ./scripts/login-facebook-group-rpa-wsl.sh
```

Browser se mo Facebook. Dang nhap bang account co quyen dang bai vao group muc tieu.
Sau khi thay Facebook home/profile, quay lai terminal va bam Enter.

Session se duoc luu tai:

```text
~/.recruitment-rpa/facebook-group-tool/storage/facebook-session.json
```

File nay bi `.gitignore`, khong commit len source.

## 3. Chay RPA tool server

```bash
cd /mnt/c/Users/sagit/OneDrive/Desktop/Source/webhook
bash ./scripts/start-facebook-group-rpa-wsl.sh
```

Mac dinh tool lang nghe:

```text
http://127.0.0.1:18990/tools/invoke
```

Spring Boot da cau hinh mac dinh goi URL nay qua:

```yaml
facebook.group-posting.rpa-tools-invoke-url
```

## 4. Test health

```bash
curl http://127.0.0.1:18990/health
```

Can thay:

```json
{
  "ok": true,
  "tool": "facebookGroupPost",
  "sessionExists": true
}
```

Selector cua Facebook UI duoc tach ra tai:

```text
config/facebook-selectors.json
```

Neu Facebook doi UI nhe, uu tien sua selector/text trong file nay roi restart RPA tool, khong can sua code.
Co the override bang bien moi truong:

```bash
FACEBOOK_RPA_SELECTORS_CONFIG=/path/to/facebook-selectors.json npm run start
```

## 5. Test post truc tiep

Dung group test truoc, tranh dang that vao group production.

```bash
curl -X POST http://127.0.0.1:18990/tools/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "facebookGroupPost",
    "action": "post",
    "sessionKey": "manual-test",
    "args": {
      "jobDescriptionId": 1,
      "groupId": 1,
      "groupName": "Test Group",
      "groupReference": "https://www.facebook.com/groups/YOUR_GROUP_ID_OR_SLUG",
      "content": "Test bai dang tu AI Recruitment Assistant."
    }
  }'
```

Neu thanh cong, response co `success=true`.
Neu that bai, tool luu screenshot/html trong:

```text
rpa/facebook-group-tool/artifacts
```

## 6. Chay qua Messenger HR

Can chay dong thoi:

```bash
cmd /c ngrok http 8080
mvn "-Dspring-boot.run.profiles=postgres" spring-boot:run
cd /mnt/c/Users/sagit/OneDrive/Desktop/Source/webhook && bash ./scripts/start-facebook-group-rpa-wsl.sh
```

Sau do HR nhan:

```text
dang group <jobId>
```

Hoac:

```text
dang group
```

Bot se hoi JD can dang neu chua xac dinh duoc.

## Luu y production

Facebook khong khuyen khich automation UI vao group. Can dung tai khoan that, group test, tan suat thap, delay lon.
Neu Facebook bat checkpoint/2FA, chay lai `npm run login` de cap nhat session.
