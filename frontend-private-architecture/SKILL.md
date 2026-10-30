---
name: frontend-private-architecture
description: >-
  Tổ chức hoặc mở rộng frontend với React/TypeScript theo kiến trúc
  frontend_tn1983: feature modules, Axios, React Query, Zustand và form Zod.
  Dùng khi cần áp dụng kiến trúc này sang dự án khác hoặc phát triển tính năng
  theo cùng quy ước; không áp đặt lên dự án đã có kiến trúc khác nếu chưa được yêu cầu.
---

# Frontend Private Architecture

Skill này chắt lọc mã thực tế của `frontend_private` trong TN1983 CRM.
Các tài liệu đi kèm tự chứa thông tin để có thể sao chép nguyên thư mục skill
sang dự án khác, không cần mang theo repository gốc.

## Đọc theo nhu cầu

- Khi tổ chức thư mục, chọn công nghệ, viết trang, form hoặc thêm nghiệp vụ:
  đọc [references/organization.md](references/organization.md).
- Khi tích hợp backend, xử lý request/response, cache, đăng nhập hoặc refresh:
  đọc [references/api-auth.md](references/api-auth.md).
- Khi cần hiểu toàn bộ kiến trúc hoặc dựng dự án theo mẫu: đọc cả hai.

Các đường dẫn `src/...` trong references thuộc frontend gốc, là dấu vết để
đối chiếu, không phải file bắt buộc tồn tại ở dự án đích. Phân biệt rõ mô tả
hiện trạng với đề xuất áp dụng; không sao chép hạn chế của mẫu thành yêu cầu.

## Hợp đồng kiến trúc

```text
Page/component → feature hook → domain API → shared Axios client → backend
                     ↓                              ↓
              React Query cache              auth + error handling

Form → React Hook Form + Zod → request DTO → mutation hook
Auth session → Zustand trong bộ nhớ; UI tạm thời → state cục bộ
```

1. Gom trang, hook, schema, kiểu nghiệp vụ và query keys theo `features/<domain>/`.
   Giữ giao tiếp HTTP trong `api/<domain>.api.ts`, cấu hình HTTP trong `api/client.ts`.
2. API trả dữ liệu nghiệp vụ đã bỏ envelope. Page không biết `AxiosResponse`
   hoặc `response.data.data`; page không tự gọi Axios/fetch tới backend.
3. Dùng React Query cho dữ liệu máy chủ, Zustand cho phiên/client state cần
   chia sẻ, React Hook Form cho form và `useState` cho trạng thái giao diện cục bộ.
   Không tạo thêm bản sao danh sách backend trong store.
4. Hook đọc khai báo key chứa đủ tham số ảnh hưởng kết quả. Hook ghi sở hữu
   cập nhật hoặc invalidation cache. UI sở hữu modal, thông báo và điều hướng.
5. Khai báo riêng response DTO, request DTO và form values khi khác nhau.
   Schema form không thay thế kiểm tra/ủy quyền ở backend.
6. Dùng shared component hiện có cho modal/layout. Thành phần chỉ phục vụ một
   trang có thể ở cùng file; tách khi có tái sử dụng hoặc trách nhiệm độc lập.

## Áp dụng sang dự án khác

Trước khi sửa, đọc package/config, cấu trúc hiện có, hợp đồng backend và một
luồng nghiệp vụ tương tự. Liệt kê phạm vi cần chạm: DTO/schema, API, keys,
hooks, page, route/menu, cache liên quan và các kiểm tra hiện có.

Với dự án mới được yêu cầu theo mẫu, dùng cây thư mục trong organization.
Với dự án đang có, ghép các trách nhiệm vào cấu trúc hiện hữu; chỉ di chuyển
toàn bộ kiến trúc khi người dùng yêu cầu. Không tự thêm service/repository,
generic CRUD factory, dependency hoặc module chưa cần.

Chốt những phần phụ thuộc backend trước khi viết: base URL, endpoint/method,
envelope, phân trang, lỗi, cơ chế token/cookie, quyền truy cập. Không mặc định
mọi backend có `/api/v1`, `code/message/data`, ADMIN/CUSTOMER hoặc PATCH.

Với một tính năng CRUD, hoàn thiện luồng nhỏ nhất:

- Kiểu dữ liệu và schema → API methods → query keys → query/mutation hooks.
- Trang danh sách có loading/error/empty/success, form có validation, thao tác
  ghi có pending/error và chỉ đóng form sau thành công.
- Gắn route và menu nếu tính năng có trang; cập nhật các cache bị ảnh hưởng,
  kể cả thống kê hoặc lookup ở nghiệp vụ khác khi cần.
- Giữ phân quyền tại backend; guard frontend chỉ kiểm soát trải nghiệm UI.

Khi triển khai auth, giữ refresh dùng client riêng, chia sẻ một promise cho
các request đồng thời, giới hạn replay một lần và loại trừ endpoint auth.
Đọc các giới hạn trong api-auth trước khi tái sử dụng.

## Kiểm tra và bàn giao

Chạy lệnh lint/build hiện có của dự án đích cho thay đổi mã. Với mẫu gốc là
`npm run lint` và `npm run build` trong `frontend_private`; build có TypeScript
check. Logic mới đáng kể cần kiểm tra hành vi nhỏ, ưu tiên công cụ test đã có.

Kiểm tra các luồng bị chạm: dữ liệu rỗng/lỗi, lọc và phân trang, validation,
mutation thành công/thất bại và cache. Nếu sửa auth, kiểm tra reload, nhiều
401 đồng thời, refresh thất bại, 403, logout và đổi tài khoản. Chỉ báo đã chạy
những gì thực sự chạy; không suy ra hoạt động runtime từ build thành công.

Khi chỉ tạo/cập nhật skill, chạy `quick_validate.py` của skill-creator nếu có,
kiểm tra các liên kết references và nội dung không còn scaffold. Không cần
build frontend nếu mã ứng dụng không đổi.
