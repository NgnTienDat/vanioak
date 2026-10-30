# API, request, response, cache và xác thực

## 1. Hợp đồng HTTP

Nguồn: `src/api/client.ts`, `src/api/*.api.ts`, `src/types/api.types.ts`.

Hai Axios instance cùng base URL `import.meta.env.VITE_API_BASE_URL ?? ''`,
`withCredentials: true`, JSON Content-Type. `apiClient` có interceptors;
`refreshClient` tách riêng để refresh không tự kích hoạt vòng refresh khác.
Hiện không cấu hình timeout, AbortSignal, retry backoff hoặc upload/download.
Chỉ bổ sung khi tác vụ cần, không mô tả chúng là khả năng có sẵn.

API object theo domain chứa async methods, DTO input/output tường minh:

```ts
export type ApiResponse<T> = {
  code: number
  message: string
  data: T
}

// Minh họa đúng cách tổ chức gốc; import từ client và DTO của feature.
export const productApi = {
  async create(request: CreateProductRequest): Promise<Product> {
    const response = await apiClient.post<ApiResponse<Product>>(
      '/api/v1/products', request,
    )
    return response.data.data
  },
}
```

`response.data` đầu là body Axios, `.data` sau là payload backend. Không
unwrap toàn cục ở interceptor rồi lại unwrap trong API. Method delete/logout
trả `Promise<void>` dù response được type là `ApiResponse<null>`.

Generic TypeScript chỉ kiểm tra lúc biên dịch, không xác thực JSON runtime.
Mã gốc không dùng Zod parse response và không kiểm business `code` cho mọi
HTTP 2xx. Nếu backend đích trả business failure trong HTTP 200, phải xử lý
theo hợp đồng tại API boundary, không mặc định HTTP thành công là nghiệp vụ
thành công. Không bịa giá trị success code hoặc error codes.

| Domain | API thực tế |
| --- | --- |
| Auth | POST `/api/v1/auth/login`, `/refresh`, `/logout`; GET `/me` |
| Products | GET/POST collection, GET/PATCH/DELETE `/:id` |
| Customers | GET/POST collection, PATCH/DELETE `/:id` |
| Users | GET/POST collection, GET/PUT/DELETE `/:id` |
| Orders | GET/POST collection, GET/PATCH/DELETE `/:id`, PATCH `/:id/status` |
| Statistics | GET `/api/v1/statistics/dashboard` |

Collection ở `/api/v1/products`, `/customers`, `/users`, `/orders`.
Không chuẩn hóa PUT/PATCH theo sở thích, giữ đúng backend từng endpoint.

GET dùng Axios `params`. Customer gửi `{ page, size, q: keyword || undefined }`;
order gửi `OrderSearchParams` với `keyword`, không phải `q`; product gửi `active`.
Payload JSON truyền làm đối số body, ID nằm path. Không tự ghép query string.

Customer/OrderPage: `{ content, totalElements, totalPages, size, number }`.
Product/user trả mảng. Không ép mọi endpoint dùng chung pagination envelope.
DTO ở feature giữ ID string, timestamp string, số tiền/khối lượng number,
nullable/optional theo từng field. Hợp đồng tiền và cách làm tròn của dự án
đích cần được xác nhận, không lấy phép tính preview ở UI làm số liệu quyết toán.

## 2. Chuẩn hóa lỗi

`src/lib/apiError.ts` cung cấp `ApiError extends Error` với `status` (HTTP),
`code` (backend) và `message`. `normalizeApiError(error: unknown)`:

- Đã là ApiError thì trả nguyên instance.
- Không phải AxiosError thì trả thông báo chung.
- AxiosError có HTTP 401 thì trả thông báo sai số điện thoại/mật khẩu.
- Các lỗi khác dùng message backend hoặc fallback, giữ status/code nếu có.

Response interceptor của `apiClient` reject ApiError, hooks dùng error generic
`ApiError`, UI đọc `.error.message`. Lỗi refresh trong `refreshAccessToken`
cũng được chuẩn hóa. `authApi.refresh()` gọi refreshClient trực tiếp, trả
LoginResponse nhưng không cập nhật store và không chuẩn hóa lỗi qua interceptor
chính; hiện không có caller trong src. Khi thêm caller, dùng luồng
`refreshAccessToken` nếu cần phục hồi phiên, tránh tạo luồng refresh thứ hai.

Giới hạn khi tái dùng: thông báo 401 của mẫu phù hợp login nhưng dễ gây nhầm
cho phiên hết hạn. Chọn message theo ngữ cảnh ở dự án đích. Lỗi mạng có thể
không có status; 403 không refresh. Không render HTML từ message backend.
Không coi type assertion trong normalizer là validation payload runtime.

## 3. Server state và query keys

Nguồn: `src/lib/queryClient.ts`, `src/lib/queryKeys.ts`,
`src/features/*/*.keys.ts`, các file `use*.ts`.

Một QueryClient singleton, default queries `retry: false`. Không cấu hình
global staleTime/gcTime; giữ mặc định thư viện. `useCurrentUser` riêng có
staleTime 5 phút. Không mô tả retry của interceptor refresh là retry query.

Key factories trả tuple readonly bằng `as const`, root key cho invalidation:

```ts
export const ORDER_QUERY_KEYS = {
  all: ['orders'] as const,
  list: (params: OrderSearchParams) =>
    [...ORDER_QUERY_KEYS.all, 'list', params] as const,
  detail: (id: string) => [...ORDER_QUERY_KEYS.all, 'detail', id] as const,
} as const
```

Đoạn trên minh họa với type params của feature; bản gốc khai báo shape params
ngay trong keys. Product list key chứa active, customer list chứa page/size/
keyword, user list có `'list'`. Không đổi key chỉ để mọi domain giống nhau;
đảm bảo đủ tham số và list/detail không đụng nhau.

- Query hook chỉ rõ `queryKey`, `queryFn`, data type và ApiError.
- Customers/orders dùng `placeholderData: keepPreviousData` để giữ trang cũ
  trong lúc chuyển trang; UI vẫn phải thể hiện đang fetch.
- Detail orders dùng `enabled: Boolean(id)` và key tạm khi chưa chọn ID;
  user detail nhận tham số enabled. Không gọi endpoint với ID rỗng.
- Mutation dùng `useMutation<Result, ApiError, Variables>`. Update nhiều đối
  số gom thành `{ id, request }`, hook gọi API tương ứng.
- On success invalidates root domain; update user/order và change order status
  còn `setQueryData(detail(id), result)` trước invalidation. `await` invalidation
  giúp caller đợi hoàn tất phần cập nhật cache theo luồng hook.
- Không có optimistic update. Page gọi `mutateAsync` để đóng modal/báo thành
  công sau mutation; lỗi lấy từ mutation state.

Giới hạn gốc: mutation orders/customers/products/users chỉ invalidate domain
của mình, chưa tự invalidate dashboard hoặc lookup domain khác. Khi tính năng
mới làm dữ liệu liên quan thay đổi, cập nhật đúng cache bị ảnh hưởng; không
copy giới hạn này rồi khẳng định thống kê tự đồng bộ.

## 4. Phiên đăng nhập

Nguồn: `src/store/authStore.ts`, auth hooks, `src/api/client.ts`,
`src/routes/AppRoutes.tsx`, `src/components/layout/AdminRoute.tsx`.

AuthStore không dùng persist/localStorage/sessionStorage. State gồm
`accessToken`, `user` và actions `setAccessToken`, `setUser`, `setAuth`,
`clearAuth`. Axios ngoài React đọc `getState()`; component dùng selector.
`clearAuth` đặt cả token và user về null.

LoginRequest `{ phone, password }`. LoginResponse gồm accessToken, tokenType,
expiresIn và CurrentUser. Role hiện là ADMIN/CUSTOMER. Client dùng literal
Bearer; chưa lập lịch refresh theo expiresIn.

Luồng login:

1. LoginPage validate bằng React Hook Form + Zod.
2. `useLogin` gọi authApi.login, xóa cache current-user cũ, `setAuth(token, user)`,
   seed `QUERY_KEYS.CURRENT_USER = ['current-user']` bằng user trả về.
3. Callback UI chuyển ADMIN tới dashboard, CUSTOMER tới 403.

`useCurrentUser` chỉ enabled khi có access token mà store chưa có user;
query gọi `/me` rồi `setUser`. Vì login/refresh đã trả user nên không phải
lần đăng nhập nào cũng có request `/me`. User hiện được giữ cả trong store
và cache current-user; đây là ngoại lệ phiên của mẫu, không phải lý do đưa
mọi entity backend vào Zustand.

Luồng reload trang được bảo vệ:

1. Access token memory mất khi reload. AppRoutes thấy path khác login, chưa
   có token, session idle và restore chưa chạy thì gọi refreshAccessToken.
2. Ref `restoreStarted` và session status giới hạn restore trong vòng đời
   component; status idle/restoring/restored/failed điều khiển màn hình chờ.
3. Refresh thành công đặt token/user; lỗi restore chuyển login.
4. AdminRoute chỉ render nội dung với user ADMIN. Frontend guard không thay
   quyền kiểm tra token, role và dữ liệu ở backend.

Frontend gửi cookie nhờ withCredentials; không tự đọc hoặc lưu refresh token.
Thuộc tính HttpOnly/Secure/SameSite, thời hạn và CORS nằm phía server, không
thể kết luận chỉ từ mã frontend. Khi áp dụng phải khớp hợp đồng backend.

## 5. Refresh khi 401 và concurrency

Request interceptor lấy token hiện tại và gắn `Authorization: Bearer ...`.
Response interceptor chỉ thử refresh khi đồng thời thỏa:

- HTTP status là 401, có original request.
- Request chưa có `_retry` và đã mang Authorization.
- URL không thuộc login, refresh, logout.

Nó đánh `_retry = true` trước khi chờ refresh. `refreshAccessToken` dùng một
`refreshPromise` module-level: request đầu POST `/auth/refresh` bằng refreshClient,
request đồng thời chờ cùng promise. Thành công lấy token/user đặt vào store,
trả token. Thất bại clearAuth và throw lỗi chuẩn hóa. Finally đặt promise về
null để lần sau có thể refresh. Các request chờ gắn token mới rồi replay
qua apiClient; 401 lần nữa không được refresh tiếp.

Đây là cơ chế chia sẻ refresh trong một instance ứng dụng/tab, không phải
đồng bộ đa tab. Mã gốc chưa bảo vệ race logout/đổi tài khoản trong lúc refresh
đang chạy; không hứa single promise giải quyết mọi race. Khi thay đổi auth,
kiểm tra tình huống đó và ngăn kết quả phiên cũ ghi đè phiên mới nếu cần.
Replay mutation chỉ an toàn khi backend từ chối request chưa xác thực trước
mọi side effect; hệ thống cần bảo đảm không ghi trùng theo hợp đồng của nó.

## 6. Logout và cache giữa các phiên

`useLogout` gọi authApi.logout; `onSettled` luôn clearAuth và remove current-user
query, kể cả logout HTTP thất bại. AppRoutes đưa về login sau settled. Điều
này đảm bảo local session được xóa nhưng không chứng minh server đã thu hồi
refresh cookie nếu request logout lỗi.

Mẫu chưa xóa toàn bộ cache nghiệp vụ khi logout/login. Khi áp dụng cho hệ
thống nhiều tài khoản/tenant, dọn hoặc phân vùng cache nhạy cảm theo phiên;
không để tài khoản mới nhìn dữ liệu cache cũ. Nhánh back-to-login ở trang
403 hiện chỉ clearAuth rồi navigate, không gọi logout backend.

Các điểm trên là giới hạn hiện trạng để người áp dụng quyết định đúng; skill
không tự sửa auth/cookie/server hoặc mở rộng phạm vi công việc người dùng.
