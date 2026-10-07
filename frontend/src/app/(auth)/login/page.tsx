import { Suspense } from "react";
import { LoginForm } from "./login-form";

export const metadata = { title: "로그인" };

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
