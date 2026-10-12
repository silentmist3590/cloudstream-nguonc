# CloudStream 3 Extension: Phim NguonC

Dự án cung cấp extension xem phim trực tiếp từ API **phim.nguonc.com** cho ứng dụng **CloudStream 3** (Android & Android TV).

---

## ⚠️ LƯU Ý QUAN TRỌNG VỀ CLOUDSTREAM REPOSITORY:
Ứng dụng CloudStream 3 trên điện thoại / TV **chỉ có thể cài đặt file tiện ích đã biên dịch (.cs3)**. Ứng dụng không thể đọc code thô trực tiếp từ liên kết web phát triển nội bộ.

---

## 🚀 HƯỚNG DẪN 3 BƯỚC ĐỂ TẠO KHO CLOUDSTREAM HOÀN CHỈNH:

### Bước 1: Tạo Repository trên GitHub
1. Đăng nhập vào [GitHub.com](https://github.com) (miễn phí).
2. Tạo một Repository mới, ví dụ đặt tên là: `cloudstream-nguonc` (chọn chế độ **Public**).

### Bước 2: Tải mã nguồn lên GitHub
1. Giải nén file zip này.
2. Mở file `repo.json`, thay `YOUR_GITHUB_USERNAME` và `YOUR_REPO_NAME` bằng tài khoản và tên repo của bạn (ví dụ: `nguyenvana/cloudstream-nguonc`).
3. Đẩy toàn bộ các thư mục và file lên GitHub:
   - Thư mục `.github/workflows/build.yml` (sẽ tự động biên dịch file .cs3 cho bạn hoàn toàn tự động!)
   - Thư mục `PhimNguonC/src/main/kotlin/com/lagradost/PhimNguonCProvider.kt`
   - File `build.gradle.kts`, `settings.gradle.kts`, `repo.json`

### Bước 3: Cài vào CloudStream 3
1. Vào tab **Actions** trên GitHub, chờ khoảng 1-2 phút cho workflow chạy xong.
2. Workflow sẽ tự động tạo một nhánh mới tên là `builds` chứa file `PhimNguonC.cs3` và `plugins.json`.
3. URL kho lưu trữ của bạn sẽ là:
   ```text
   https://raw.githubusercontent.com/TÊN_BẠN/cloudstream-nguonc/builds/repo.json
   ```
4. Mở CloudStream -> Cài đặt -> Phần mở rộng -> Thêm kho lưu trữ -> Dán link trên vào là xong!
