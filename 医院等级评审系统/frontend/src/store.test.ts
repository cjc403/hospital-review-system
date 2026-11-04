import { acceptSession, clearSession } from "./auth";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { busy, loadWorkspace, mutate, notice, role, workspace, reopenImprovement } from "./store";
import { ApiError, request } from "./api";
import type { Workspace } from "./types";

const fixture: Workspace = {
  meta: {
    hospitalName: "合成医院",
    standard: "样例标准",
    period: "2026-09",
    demo: true,
    dataAsOf: "2026-09-30",
    totalMonitoringClauses: 203,
  },
  departments: [],
  indicators: [],
  batches: [],
  manualValues: [],
  reconciliations: [],
  auditSamples: [],
  prerequisites: [],
  improvements: [],
  policies: [],
  scoreRuns: [],
  evidenceFiles: [],
  auditLogs: [],
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

beforeEach(() => {
  vi.useFakeTimers();
  acceptSession({token:"test-session",user:{id:"quality",username:"review",displayName:"评审办",roleCode:"review_office",roleName:"评审办管理员",hospitalId:"H001",hospitalName:"合成医院",deptCode:"",deptName:"",permissions:["legacy.write"],legacyRole:"quality",clauseCodes:[],status:"active"}});
  busy.value = false;
  role.value = "quality";
  workspace.value = null;
  notice.value = null;
});
afterEach(() => {
  clearSession();
  vi.unstubAllGlobals();
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe("真实接口边界与工作区刷新", () => {
  it("重新打开改进单发送后端重开命令并重新读取工作区", async () => {
    const calls: { path: string; options: RequestInit }[] = [];
    vi.stubGlobal("fetch", async (path: string, options: RequestInit) => {
      calls.push({path, options});
      return path === "/api/workspace" ? json(fixture) : json({id:"im-closed",status:"opened"});
    });
    expect(await reopenImprovement("im-closed")).toBe(true);
    expect(calls[0]!.path).toBe("/api/improvements/im-closed");
    expect(calls[0]!.options.method).toBe("PATCH");
    expect(JSON.parse(calls[0]!.options.body as string)).toEqual({status:"reopen"});
    expect(calls[1]!.path).toBe("/api/workspace");
  });
  it("写入发送会话与JSON，成功后重新读取后端工作区", async () => {
    const calls: { path: string; options: RequestInit }[] = [];
    vi.stubGlobal("fetch", async (path: string, options: RequestInit) => {
      calls.push({ path, options });
      return path === "/api/workspace"
        ? json(fixture)
        : json({ id: "manual-1" });
    });
    expect(
      await mutate("/manual-values", { value: 0.35, note: "合成补录" }),
    ).toBe(true);
    expect(calls.map((x) => x.path)).toEqual([
      "/api/manual-values",
      "/api/workspace",
    ]);
    expect((calls[0]!.options.headers as Headers).get("Authorization")).toBe("Bearer test-session");
    expect((calls[0]!.options.headers as Headers).has("X-Demo-Role")).toBe(false);
    expect((calls[0]!.options.headers as Headers).get("Content-Type")).toBe(
      "application/json",
    );
    expect(JSON.parse(calls[0]!.options.body as string)).toEqual({
      value: 0.35,
      note: "合成补录",
    });
    expect(workspace.value).toEqual({ ...fixture, meta: { ...fixture.meta, hospitalName: "医院" } });
    expect(fixture.meta.hospitalName).toBe("合成医院");
    expect(busy.value).toBe(false);
  });

  it("同一写入完成前拒绝重复点击，失败后解除禁用并保留服务器错误", async () => {
    let release: (value: Response) => void = () => {};
    let writes = 0;
    vi.stubGlobal("fetch", () => {
      writes++;
      return new Promise<Response>((resolve) => (release = resolve));
    });
    const first = mutate("/manual-values/m1/review", { decision: "approved" });
    expect(busy.value).toBe(true);
    expect(
      await mutate("/manual-values/m1/review", { decision: "approved" }),
    ).toBe(false);
    expect(writes).toBe(1);
    release(json({ message: "该记录已审核，不可重复审核" }, 409));
    expect(await first).toBe(false);
    expect(busy.value).toBe(false);
    expect(notice.value?.message).toBe("该记录已审核，不可重复审核");
    expect(notice.value?.error).toBe(true);
  });

  it("较慢的旧工作区请求不能覆盖新请求数据", async () => {
    let release: (value: Response) => void = () => {};
    let calls = 0;
    vi.stubGlobal("fetch", () => {
      return ++calls === 1
        ? new Promise<Response>((resolve) => (release = resolve))
        : Promise.resolve(
            json({
              ...fixture,
              meta: { ...fixture.meta, hospitalName: "本科汇总" },
            }),
          );
    });
    const old = loadWorkspace();
    role.value = "department";
    await loadWorkspace();
    release(json(fixture));
    await old;
    expect(workspace.value?.meta.hospitalName).toBe("本科汇总");
  });

  it("403权限拒绝显示后端原因，而非空数据或假成功", async () => {
    vi.stubGlobal("fetch", async () =>
      json({ message: "当前角色不允许查看成员明细" }, 403),
    );
    await expect(request("/indicators/i01/members", "leader")).rejects.toEqual(
      new ApiError("当前角色不允许查看成员明细", 403),
    );
  });
});
