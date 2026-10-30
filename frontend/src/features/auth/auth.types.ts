export type Role = 'ADMIN' | 'ENGINEER'
export type User = { id: string; username: string; role: Role }
export type TokenPair = { access_token: string; refresh_token: string; token_type: 'Bearer'; expires_in: number }
export type LoginRequest = { username: string; password: string }
export type LoginResponse = TokenPair & { user: User }
