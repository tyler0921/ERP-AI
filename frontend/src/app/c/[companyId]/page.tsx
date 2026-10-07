import { redirect } from "next/navigation";

export default async function CompanyIndex({ params }: PageProps<"/c/[companyId]">) {
  const { companyId } = await params;
  redirect(`/c/${companyId}/dashboard`);
}
