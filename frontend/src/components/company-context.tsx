"use client";

import { createContext, useContext } from "react";
import { canWrite, type Company } from "@/lib/types";

interface CompanyCtx {
  company: Company;
  /** "/companies/{id}" — API 경로 prefix */
  base: string;
  canWrite: boolean;
  isAdmin: boolean;
  reloadCompany: () => void;
}

const Ctx = createContext<CompanyCtx | null>(null);

export function CompanyProvider({ company, reloadCompany, children }: { company: Company; reloadCompany: () => void; children: React.ReactNode }) {
  return (
    <Ctx.Provider
      value={{
        company,
        base: `/companies/${company.id}`,
        canWrite: canWrite(company.role),
        isAdmin: company.role === "ADMIN",
        reloadCompany,
      }}
    >
      {children}
    </Ctx.Provider>
  );
}

export function useCompany(): CompanyCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useCompany는 CompanyProvider 안에서만 사용할 수 있습니다.");
  return ctx;
}
