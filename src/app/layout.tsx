import type { Metadata } from "next";
import { Geist, Geist_Mono, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const jetbrains = JetBrains_Mono({
  variable: "--font-jetbrains",
  subsets: ["latin"],
  weight: ["400", "500", "700", "800"],
});

export const metadata: Metadata = {
  title: "NOID//MOBILE — Parano1d Miner + Wallet",
  description:
    "Web + Android miner and wallet for Parano1d (NOID) — proof-native Layer 1 PoW blockchain. Poseidon2b hashing, JSON-RPC node client, Stratum V2 pool support.",
  keywords: [
    "Parano1d",
    "NOID",
    "miner",
    "wallet",
    "Poseidon2b",
    "PoW",
    "post-quantum",
    "blockchain",
  ],
  authors: [{ name: "SecretArrow" }],
  icons: {
    icon: "/logo.svg",
  },
  openGraph: {
    title: "NOID//MOBILE",
    description: "Parano1d (NOID) web + Android miner and wallet",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning className="dark">
      <body
        className={`${geistSans.variable} ${geistMono.variable} ${jetbrains.variable} antialiased bg-background text-foreground min-h-screen`}
      >
        {children}
        <Toaster />
      </body>
    </html>
  );
}
