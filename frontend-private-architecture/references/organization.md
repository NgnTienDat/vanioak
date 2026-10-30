# Cấu trúc và quy ước frontend_private

## 1. Công nghệ thực tế

Các phiên bản dưới đây là range khai báo trong `package.json` tại thời điểm
đọc mã, không phải khuyến nghị cài phiên bản mới nhất hoặc phiên bản đã resolve.

| Công nghệ | Range khai báo | Trách nhiệm |
| --- | --- | --- |
| React / React DOM | ^19.2.8 | Functional components, hooks, render SPA |
| TypeScript | ~6.0.2 | Kiểu DTO, props, form, keys |
| Vite / plugin-react | ^8.2.2 / ^6.1.0 | Dev server, bundling, React integration |
| Tailwind CSS / Vite plugin | ^4.3.3 | Utility classes, responsive UI |
| Axios | ^1.20.0 | HTTP clients, interceptors |
| TanStack React Query | ^5.102.8 | Server state, cache, query và mutation |
| Zustand | ^5.0.15 | Access token và user của phiên trong bộ nhớ |
| React Hook Form | ^7.87.0 | Form state, submit, field arrays |
| Zod / hookform resolvers | ^4.5.4 / ^5.9.1 | Schema và tích hợp validation |
| Recharts | ^3.10.1 | Biểu đồ dashboard |
| react-router-dom | ^7.18.3 | Đã cài, chưa được import trong mã src hiện tại |
| Oxlint | ^1.79.0 | Lint React/TypeScript/Oxc |

Không thấy Redux, UI component framework, CSS Modules, SSR hoặc test runner
được cấu hình trong frontend này. Không tự thêm chúng để mô phỏng kiến trúc.

## 2. Cây tổ chức

```text
frontend_private/
├── package.json
├── vite.config.ts
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
├── .oxlintrc.json
├── index.html
├── public/                    # favicon.svg, icons.svg
└── src/
    ├── main.tsx               # StrictMode + QueryClientProvider
    ├── App.tsx                # Render AppRoutes
    ├── index.css              # @import "tailwindcss"
    ├── routes/AppRoutes.tsx   # Route map, navigation, restore session
    ├── api/
    │   ├── client.ts          # apiClient, refreshClient, refreshAccessToken
    │   └── <domain>.api.ts    # auth, product, order, customer, user, statistic
    ├── lib/
    │   ├── apiError.ts        # ApiError + normalizeApiError
    │   ├── queryClient.ts     # QueryClient singleton
    │   └── queryKeys.ts       # CURRENT_USER dùng chung
    ├── store/authStore.ts     # Zustand session
    ├── types/
    │   ├── api.types.ts       # ApiResponse<T>
    │   └── auth.types.ts      # LoginRequest/Response, CurrentUser
    ├── components/
    │   ├── common/            # BaseModal, FormModal, ConfirmDialog
    │   └── layout/            # AdminLayout, AdminRoute
    └── features/
        ├── auth/             # LoginPage, ForbiddenPage, schema, auth hooks
        ├── dashboard/        # Page, statistics hook, types, keys
        ├── settings/         # SettingsPage tĩnh
        ├── products/
        │   ├── ProductListPage.tsx
        │   ├── product.types.ts
        │   ├── productSchema.ts
        │   ├── product.keys.ts
        │   ├── useProducts.ts
        │   ├── useCreateProduct.ts
        │   ├── useUpdateProduct.ts
        │   └── useDeleteProduct.ts
        ├── customers/        # Cùng mẫu, có phân trang/tìm kiếm
        ├── users/            # Cùng mẫu, thêm useUser cho detail
        └── orders/           # Cùng mẫu, thêm detail và change status
```

Đây là cách tổ chức lai: hạ tầng dùng chung theo layer, mã nghiệp vụ gom theo
feature. API nằm ngoài feature nhưng import DTO của feature. Không phải kiến
trúc với dependency một chiều tuyệt đối hay hệ thống module độc lập hoàn toàn.

## 3. Ranh giới trách nhiệm

| Nơi | Đặt gì ở đây | Tránh đặt |
| --- | --- | --- |
| `api/client.ts` | Base URL, credentials, bearer, refresh, chuẩn hóa lỗi | JSX, modal, thông báo thành công |
| `api/<domain>.api.ts` | Endpoint, method, params/body, unwrap response | React hooks, điều hướng, cache invalidation |
| `features/<domain>/use*.ts` | useQuery/useMutation, keys, cache, enabled | JSX và state modal |
| `features/<domain>/*Page.tsx` | Ghép hooks, UI, filter, form, modal, notice | HTTP hoặc xử lý envelope |
| `*.types.ts` | DTO, enum union, params và kiểu phân trang | HTTP side effects |
| `*Schema.ts` | Zod schema, `z.infer` form values | Gọi backend |
| `components/common` | UI dùng chung nhiều feature | Endpoint hoặc quy tắc riêng một nghiệp vụ |
| `components/layout` | Khung admin, navigation, guard | CRUD chi tiết |
| `lib` | Tiện ích hạ tầng và singleton dùng chung | Mọi helper của từng trang |
| `store` | Client/session state chia sẻ | Bản sao các danh sách React Query |

Các page gốc vẫn có mapping DTO, tính tổng, nhãn enum, helper format và subcomponent
cục bộ. Mục tiêu tách logic khỏi UI chưa được áp dụng triệt để. Khi mở rộng,
giữ logic request/cache ở API/hook; chỉ tách phần còn lại khi giúp làm rõ trách
nhiệm, không tạo layer mới cho mọi biểu thức.

## 4. Naming, imports và TypeScript

- Thư mục feature số nhiều, chữ thường: `products`, `customers`, `orders`, `users`.
- Page/component PascalCase: `ProductListPage.tsx`, `BaseModal.tsx`.
- Hook camelCase, bắt đầu `use`, thường một hook/file: `useUpdateProduct.ts`.
- API singular + `.api.ts`; DTO và keys singular + `.types.ts`/`.keys.ts`.
- Schema camelCase: `productSchema.ts`; đối tượng export cùng tên.
- API object: `productApi`; key namespace: `PRODUCT_QUERY_KEYS`; type: `Product`,
  `CreateProductRequest`, `UpdateProductRequest`, `ProductFormValues`.
- Chủ yếu named exports; `App` dùng default export. Import trực tiếp file,
  không có barrel `index.ts` hay path alias được cấu hình. Đa số import có
  `.ts`/`.tsx`, một số file bỏ extension; không mô tả là thống nhất tuyệt đối.
- Dùng `import type`, `type` alias, string literal unions cho enum từ backend,
  `as const` cho keys/map. Phân biệt `null` và optional theo hợp đồng DTO.
- Style phổ biến: single quotes, không semicolon, thụt 2 spaces; `client.ts`
  và `queryClient.ts` còn tabs. Không có bằng chứng formatter bắt buộc.

App tsconfig dùng ES2023, module ESNext, bundler resolution, `react-jsx`, noEmit,
verbatimModuleSyntax, allowImportingTsExtensions, noUnusedLocals/Parameters,
erasableSyntaxOnly, noFallthroughCasesInSwitch. Không có `strict: true` được
khai báo; đừng khẳng định dự án bật strict mode TypeScript. `StrictMode` của
React là khái niệm khác. TS config riêng cho Node/Vite dùng NodeNext.

## 5. Form và DTO

Luồng thường gặp: `useForm<FormValues>({ resolver: zodResolver(schema) })` →
`register` → `handleSubmit` → mapping request → `mutateAsync`.

- Form values suy từ Zod bằng `z.infer`. Numeric inputs dùng `valueAsNumber`;
  array items của order dùng `useFieldArray` và `useWatch`.
- `reset(getFormValues(entity))` khi mở hoặc hủy edit; `null` từ response có
  thể đổi thành `''` ở ranh giới form. Không đổi DTO toàn cục chỉ vì input.
- Khi create, loại field chỉ dùng UI/update, ví dụ `active` của customer.
  User create có password, update không gửi password. Optional/empty/null
  phải theo đúng backend, không gửi nguyên mọi field trong form.
- Page phân biệt create/view/edit; dùng `clearErrors` và mutation `reset`
  khi cần. Không phải trang nào hiện tại cũng reset toàn bộ mutation errors.
- Show field errors và mutation error riêng; pending khóa submit/xóa. Đóng
  modal và báo thành công sau khi mutation thành công.
- Product/order có `try/catch` quanh `mutateAsync`, lỗi vẫn hiện từ mutation.
  Một số auth/customer/user handlers chưa catch rejected promise nhất quán;
  đừng sao chép thiếu sót này thành quy tắc.
- `userSchema` còn validation create nằm trong page qua `setError`; khi xây
  mới, gom rule create/update vào schema thích hợp nếu cần, không sao chép
  nguyên regex hoặc giới hạn số điện thoại của CRM sang domain khác.

Order còn có xác nhận trước tạo, lấy `editable` và `allowedNextStatuses` từ
backend, tự điền người nhận từ customer, tính tổng xem trước và số gói theo
khối lượng. Đây là nghiệp vụ cà phê, không phải yêu cầu chung của kiến trúc.

## 6. State, UI và trình bày

- React Query giữ dữ liệu backend; Zustand hiện chỉ chứa session auth.
- `useState` giữ selected entity, chế độ modal, delete target, notice, filter,
  trang hiện tại và khoảng biểu đồ. React Hook Form giữ form state.
- Customer/order phân trang server, page bắt đầu 0, UI hiển thị +1, size 10.
  Search customer debounce 500 ms, order 400 ms bằng effect + timer cleanup;
  reset page khi đổi filter. Product/user lấy mảng, không tự thêm phân trang.
- Các trang hiển thị loading, error, empty, data; nhiều trang thêm trạng thái
  background fetching. Dashboard đổi 7/30 ngày bằng slice dữ liệu đã fetch,
  không tạo request mới chỉ vì đổi lựa chọn này.
- Dùng Tailwind utility trong JSX, bảng HTML có overflow ngang, layout desktop
  sidebar/mobile navigation, label/input, select/button native.
- `BaseModal` dựng dialog; `FormModal` bọc form; `ConfirmDialog` dành xác nhận.
  Label, aria-invalid/describedby, role alert/status được dùng ở nhiều nơi.
  Modal hiện chưa có focus trap, Escape handling hoặc trả focus; khi tạo mới
  phải xử lý khả năng dùng bàn phím theo yêu cầu, không coi aria là đã đủ.
- UI nội bộ ưu tiên gọn, dễ đọc: trắng/xám/đen, border mảnh; màu cho trạng thái,
  không gradient, glassmorphism, animation hoặc illustration trang trí.
  Đây cũng là chủ ý trong `.github/copilot-instructions.md`, phù hợp admin;
  không áp đặt phong cách này lên marketing site không được yêu cầu.
- Dùng `Record<Union, string>` map enum sang nhãn Việt, `Intl.NumberFormat`
  và `Intl.DateTimeFormat('vi-VN')`. Helper hiện lặp theo page. Locale, tiền
  tệ, timezone là lựa chọn dự án đích; nhãn GMT+07 ở Settings không cấu hình
  timezone của `Intl` (hiện không truyền `timeZone`).
- AdminLayout còn tên người dùng hardcode, SettingsPage chứa thông tin tĩnh.
  Không suy ra có settings API hoặc profile header động.

## 7. Routing và bootstrap

`main.tsx` tạo React root trong StrictMode, một QueryClientProvider bao App.
App chỉ render AppRoutes. AppRoutes dùng object route map, `window.location`,
`history.pushState` và listener `popstate`, truyền callback navigate xuống UI.
Có alias `/admin/...` và đường dẫn ngắn; path lạ quay về login.

Guard AdminRoute kiểm token/user, callback tới login hoặc 403, chỉ render
children khi role ADMIN. Khôi phục phiên được điều phối ở AppRoutes, xem api-auth.
Không có lazy route loading hoặc BrowserRouter trong mã đang đọc. Dù Copilot
instructions nói dùng React Router, đó chưa phải cách triển khai thực tế.
Nếu dự án đích đã dùng router, dùng guard/layout của router đó. Với SPA dùng
pathname, hosting cần fallback về index.html cho deep link/reload.

## 8. Cấu hình và kiểm tra

- `vite.config.ts`: plugin React + Tailwind; chưa có proxy, alias hay base tùy chỉnh.
- `VITE_API_BASE_URL` được đọc ở client; fallback `''` nghĩa là cùng origin.
  Biến `VITE_*` nằm trong bundle public, không chứa secret. Không sao chép `.env`
  hoặc hostname của dự án gốc. CORS/cookie phía server phải phù hợp deployment.
- `.oxlintrc.json`: plugins react/typescript/oxc, rules-of-hooks error,
  only-export-components warning (cho phép constant export). Không cấu hình
  type-aware lint trong file này.
- Scripts: dev `vite`, build `tsc -b && vite build`, lint `oxlint`, preview
  `vite preview`. Chạy trong thư mục frontend. Chưa có script test.
- `.gitignore` bỏ node_modules/dist và `*.local`; không có rule bỏ mọi `.env`.
  Không coi file environment mặc nhiên là an toàn để commit.
- README frontend là template Vite; đối chiếu mã/config khi mô tả kiến trúc.

## 9. Tài liệu cũ khác mã

`.github/copilot-instructions.md` nói access token ở localStorage, Zustand chỉ
dành UI, profile phải fetch `/auth/me`, và routing dùng React Router. Mã hiện
tại giữ token + user ở Zustand memory, login/refresh trả sẵn user nên `/me`
chỉ chạy khi có token mà chưa có user, và router dùng History API.
Keys nghiệp vụ cũng nằm từng feature thay vì tất cả ở một file trung tâm.
Khi tái dùng, lấy những ranh giới kiến trúc có ích; không biến lời mô tả cũ
thành khẳng định hiện trạng, không tự refactor repo chỉ để khớp tài liệu.
