// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// Smoke test: verifies that the web app at / renders the NOID wordmark.
import { test, expect } from '@playwright/test';

test('home page renders the NOID wordmark', async ({ page }) => {
  await page.goto('/');
  // The cyberpunk terminal UI uses the wordmark "NOID//MOBILE" or
  // "NOID" somewhere on the dashboard. We just assert the page loads
  // and contains "NOID" within 10 seconds.
  await expect(page.getByText('NOID', { exact: false }).first()).toBeVisible({
    timeout: 10_000,
  });
});
