"use client";

import React, { useState, useEffect } from "react";
import { 
  CreditCard, 
  Key, 
  Activity, 
  Code, 
  Terminal, 
  ShieldAlert, 
  CheckCircle2, 
  Clock, 
  Cpu, 
  RefreshCw, 
  HelpCircle,
  Eye,
  EyeOff,
  Database,
  Layers
} from "lucide-react";

interface Transaction {
  id: string;
  amount: number;
  currency: string;
  status: "AUTHORIZED" | "CAPTURED" | "REFUNDED" | "FAILED" | "CANCELLED";
  idempotencyKey: string;
  gatewayRef: string;
  createdAt: string;
  reasons?: string[];
}

export default function Home() {
  const [activeTab, setActiveTab] = useState<"dashboard" | "simulator" | "keys">("dashboard");
  
  // Simulator form state
  const [amount, setAmount] = useState("150.00");
  const [currency, setCurrency] = useState("USD");
  const [cardProfile, setCardProfile] = useState("success"); // success, decline, limit
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [description, setDescription] = useState("Test Payment via Sandbox Simulator");
  const [apiKeyVisible, setApiKeyVisible] = useState(false);
  
  // Simulation run state
  const [isSimulating, setIsSimulating] = useState(false);
  const [simStep, setSimStep] = useState(0);
  const [simLogs, setSimLogs] = useState<string[]>([]);
  const [simResult, setSimResult] = useState<any>(null);

  // List of payments
  const [payments, setPayments] = useState<Transaction[]>([
    {
      id: "pay_7f81a8b92cd3",
      amount: 150.00,
      currency: "USD",
      status: "CAPTURED",
      idempotencyKey: "idem_8f3c2b9a11",
      gatewayRef: "ch_gateway_a1b2c3d4",
      createdAt: "2026-07-11T11:22:15Z"
    },
    {
      id: "pay_3e12c9a1b8e4",
      amount: 12500.00,
      currency: "USD",
      status: "FAILED",
      idempotencyKey: "idem_921c83fa9b",
      gatewayRef: "",
      createdAt: "2026-07-11T11:15:30Z",
      reasons: ["Card declined: Transaction limit exceeded (Mock Gateway)"]
    },
    {
      id: "pay_9f8d7c6b5a4e",
      amount: 45.99,
      currency: "EUR",
      status: "AUTHORIZED",
      idempotencyKey: "idem_0c9b8a7d6e",
      gatewayRef: "ch_gateway_ffeeddcc",
      createdAt: "2026-07-11T10:45:00Z"
    }
  ]);

  // Roll fresh idempotency key
  const generateIdempotencyKey = () => {
    const key = "idem_" + Math.random().toString(36).substring(2, 15);
    setIdempotencyKey(key);
  };

  useEffect(() => {
    generateIdempotencyKey();
  }, []);

  // Run the step-by-step transaction simulation
  const handleSimulatePayment = (e: React.FormEvent) => {
    e.preventDefault();
    setIsSimulating(true);
    setSimStep(1);
    setSimResult(null);
    setSimLogs(["[Gateway] POST /v1/payments received. Authenticating credentials..."]);

    const cardToken = cardProfile === "decline" ? "tok_decline_card" : "tok_visa_success";
    const simAmount = parseFloat(amount);

    // Timeline steps representing microservices
    setTimeout(() => {
      setSimStep(2);
      setSimLogs(prev => [...prev, "[Gateway] API Key is valid. Rate Limiter: checks passed. Redis sliding-window: OK."]);
    }, 1000);

    setTimeout(() => {
      setSimStep(3);
      setSimLogs(prev => [...prev, `[Payment Saga] Distributed Lock acquired on key: lock:idempotency:merchant_demo_123:${idempotencyKey}`]);
      setSimLogs(prev => [...prev, "[Payment Saga] Verification: Idempotency check unique. DB entry CREATED."]);
    }, 2000);

    setTimeout(() => {
      setSimStep(4);
      setSimLogs(prev => [...prev, "[Go FraudEngine] Analysing fingerprints... IP: 127.0.0.1. Country: US."]);
      if (cardProfile === "limit" && simAmount >= 10000.0) {
        setSimLogs(prev => [...prev, "[Go FraudEngine] Risk Assessment Passed (Score: 0.15). Proceeding to issuer auth."]);
      } else {
        setSimLogs(prev => [...prev, "[Go FraudEngine] Risk Assessment Passed (Score: 0.05). Proceeding to issuer auth."]);
      }
    }, 3200);

    setTimeout(() => {
      setSimStep(5);
      setSimLogs(prev => [...prev, `[Authorization Service] Calling acquiring network with token: ${cardToken}`]);
      if (cardProfile === "decline") {
        setSimStep(99); // Failed status
        setSimLogs(prev => [...prev, "[Authorization Service] DECLINED. Reason: Insufficient funds (Mock Gateway)."]);
        setSimLogs(prev => [...prev, "[Payment Saga] Transaction failure registered. Releasing distributed locks."]);
        setIsSimulating(false);
        const newPay: Transaction = {
          id: `pay_${Math.random().toString(36).substring(2, 14)}`,
          amount: simAmount,
          currency: currency,
          status: "FAILED",
          idempotencyKey: idempotencyKey,
          gatewayRef: "",
          createdAt: new Date().toISOString(),
          reasons: ["Card declined: Insufficient funds (Mock Gateway)"]
        };
        setPayments(prev => [newPay, ...prev]);
        setSimResult(newPay);
      } else if (cardProfile === "limit" && simAmount >= 10000.0) {
        setSimStep(99); // Failed status
        setSimLogs(prev => [...prev, "[Authorization Service] DECLINED. Reason: Transaction limit exceeded (Mock Gateway)."]);
        setSimLogs(prev => [...prev, "[Payment Saga] Transaction failure registered. Releasing distributed locks."]);
        setIsSimulating(false);
        const newPay: Transaction = {
          id: `pay_${Math.random().toString(36).substring(2, 14)}`,
          amount: simAmount,
          currency: currency,
          status: "FAILED",
          idempotencyKey: idempotencyKey,
          gatewayRef: "",
          createdAt: new Date().toISOString(),
          reasons: ["Card declined: Transaction limit exceeded (Mock Gateway)"]
        };
        setPayments(prev => [newPay, ...prev]);
        setSimResult(newPay);
      } else {
        setSimLogs(prev => [...prev, "[Authorization Service] APPROVED. Gateway reference: ch_gateway_" + Math.random().toString(36).substring(2, 10)]);
      }
    }, 4500);

    // If success card, proceed with ledger & outbox
    if (cardProfile !== "decline" && !(cardProfile === "limit" && simAmount >= 10000.0)) {
      setTimeout(() => {
        setSimStep(6);
        setSimLogs(prev => [...prev, "[Ledger Service] Committing journal items..."]);
        setSimLogs(prev => [...prev, "[Ledger Service] DEBIT: SYSTEM_PAYCORE (ASSET) balance updated."]);
        setSimLogs(prev => [...prev, "[Ledger Service] CREDIT: merchant_demo_123 (LIABILITY) balance updated."]);
      }, 5800);

      setTimeout(() => {
        setSimStep(7);
        setSimLogs(prev => [...prev, "[Payment Saga] DB update: AUTHORIZED. Writing to Outbox."]);
        setSimLogs(prev => [...prev, "[Kafka Outbox] Published outbox event paycore.payment.authorized to partition key."]);
      }, 7000);

      setTimeout(() => {
        setSimStep(8);
        setSimLogs(prev => [...prev, "[Go Webhook] Kafka partition trigger caught. Queueing task..."]);
        setSimLogs(prev => [...prev, "[Go Webhook] Dispatch worker POST to merchant dashboard simulator. SHA-256 HMAC Sign: OK."]);
        setSimLogs(prev => [...prev, "[System] Payment lifecycle complete! Returning 201 Created."]);
        
        setIsSimulating(false);
        const newPay: Transaction = {
          id: `pay_${Math.random().toString(36).substring(2, 14)}`,
          amount: simAmount,
          currency: currency,
          status: "AUTHORIZED",
          idempotencyKey: idempotencyKey,
          gatewayRef: "ch_gateway_" + Math.random().toString(36).substring(2, 10),
          createdAt: new Date().toISOString()
        };
        setPayments(prev => [newPay, ...prev]);
        setSimResult(newPay);
      }, 8200);
    }
  };

  return (
    <div className="flex min-h-screen bg-[#07070a] text-white">
      {/* Sidebar navigation */}
      <aside className="w-64 bg-black/40 border-r border-white/10 flex flex-col p-6 space-y-8">
        <div>
          <div className="flex items-center space-x-2">
            <div className="h-8 w-8 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-lg shadow-lg shadow-indigo-600/30">
              P
            </div>
            <span className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-gray-400 bg-clip-text text-transparent">PayCore</span>
            <span className="text-xs px-2 py-0.5 rounded bg-white/10 text-gray-400">Sandbox</span>
          </div>
        </div>

        <nav className="flex-1 space-y-2">
          <button 
            onClick={() => setActiveTab("dashboard")}
            className={`w-full flex items-center space-x-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${activeTab === "dashboard" ? "bg-indigo-600/20 text-indigo-400 border-l-4 border-indigo-600" : "text-gray-400 hover:bg-white/5 hover:text-white"}`}
          >
            <Activity className="h-4 w-4" />
            <span>Dashboard</span>
          </button>
          <button 
            onClick={() => { setActiveTab("simulator"); generateIdempotencyKey(); }}
            className={`w-full flex items-center space-x-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${activeTab === "simulator" ? "bg-indigo-600/20 text-indigo-400 border-l-4 border-indigo-600" : "text-gray-400 hover:bg-white/5 hover:text-white"}`}
          >
            <Terminal className="h-4 w-4" />
            <span>Payment Simulator</span>
          </button>
          <button 
            onClick={() => setActiveTab("keys")}
            className={`w-full flex items-center space-x-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${activeTab === "keys" ? "bg-indigo-600/20 text-indigo-400 border-l-4 border-indigo-600" : "text-gray-400 hover:bg-white/5 hover:text-white"}`}
          >
            <Key className="h-4 w-4" />
            <span>API Keys & Config</span>
          </button>
        </nav>

        <div className="p-4 rounded-xl bg-white/5 border border-white/10 text-xs text-gray-400 space-y-2">
          <div className="flex items-center space-x-2">
            <span className="h-2 w-2 rounded-full bg-green-500 animate-pulse"></span>
            <span className="text-white font-medium">All systems online</span>
          </div>
          <p className="leading-relaxed">Core payment engine processing at &lt;40ms latency.</p>
        </div>
      </aside>

      {/* Main content body */}
      <main className="flex-1 flex flex-col min-h-screen">
        {/* Header */}
        <header className="h-16 border-b border-white/10 px-8 flex items-center justify-between bg-black/20">
          <h1 className="text-lg font-semibold">
            {activeTab === "dashboard" && "Dashboard Overview"}
            {activeTab === "simulator" && "Distributed Sandbox Simulator"}
            {activeTab === "keys" && "Merchant Keys & Config"}
          </h1>
          <div className="flex items-center space-x-4">
            <div className="text-xs text-gray-400">
              Merchant: <span className="text-white font-mono bg-white/10 px-2 py-1 rounded">merchant_demo_123</span>
            </div>
          </div>
        </header>

        {/* Tab contents */}
        <div className="flex-1 p-8 overflow-y-auto">
          {/* TAB 1: DASHBOARD OVERVIEW */}
          {activeTab === "dashboard" && (
            <div className="space-y-8">
              {/* Analytics blocks */}
              <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                <div className="p-6 rounded-2xl bg-white/5 border border-white/10 flex flex-col space-y-2 shadow-xl">
                  <span className="text-xs text-gray-400 font-medium uppercase tracking-wider">Gross Volume</span>
                  <span className="text-3xl font-bold bg-gradient-to-r from-indigo-400 to-cyan-400 bg-clip-text text-transparent">$12,745.99</span>
                  <span className="text-xs text-green-500 font-medium">+15.2% vs yesterday</span>
                </div>
                <div className="p-6 rounded-2xl bg-white/5 border border-white/10 flex flex-col space-y-2 shadow-xl">
                  <span className="text-xs text-gray-400 font-medium uppercase tracking-wider">Success Rate</span>
                  <span className="text-3xl font-bold text-white">99.98%</span>
                  <span className="text-xs text-gray-400 font-medium">Target SLA: 99.99%</span>
                </div>
                <div className="p-6 rounded-2xl bg-white/5 border border-white/10 flex flex-col space-y-2 shadow-xl">
                  <span className="text-xs text-gray-400 font-medium uppercase tracking-wider">P99 Latency</span>
                  <span className="text-3xl font-bold text-indigo-400">38ms</span>
                  <span className="text-xs text-green-500 font-medium">Optimized by Virtual Threads</span>
                </div>
                <div className="p-6 rounded-2xl bg-white/5 border border-white/10 flex flex-col space-y-2 shadow-xl">
                  <span className="text-xs text-gray-400 font-medium uppercase tracking-wider">Active Workers</span>
                  <span className="text-3xl font-bold text-emerald-400">50 / Go</span>
                  <span className="text-xs text-gray-400 font-medium">Webhook concurrent routines</span>
                </div>
              </div>

              {/* Transactions Log */}
              <div className="p-6 rounded-2xl bg-white/5 border border-white/10 space-y-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-base font-semibold">Recent Sandbox Transactions</h3>
                  <button 
                    onClick={() => {
                      setPayments([
                        {
                          id: "pay_7f81a8b92cd3",
                          amount: 150.00,
                          currency: "USD",
                          status: "CAPTURED",
                          idempotencyKey: "idem_8f3c2b9a11",
                          gatewayRef: "ch_gateway_a1b2c3d4",
                          createdAt: "2026-07-11T11:22:15Z"
                        },
                        {
                          id: "pay_3e12c9a1b8e4",
                          amount: 12500.00,
                          currency: "USD",
                          status: "FAILED",
                          idempotencyKey: "idem_921c83fa9b",
                          gatewayRef: "",
                          createdAt: "2026-07-11T11:15:30Z",
                          reasons: ["Card declined: Transaction limit exceeded (Mock Gateway)"]
                        },
                        {
                          id: "pay_9f8d7c6b5a4e",
                          amount: 45.99,
                          currency: "EUR",
                          status: "AUTHORIZED",
                          idempotencyKey: "idem_0c9b8a7d6e",
                          gatewayRef: "ch_gateway_ffeeddcc",
                          createdAt: "2026-07-11T10:45:00Z"
                        }
                      ]);
                    }}
                    className="flex items-center space-x-1 text-xs text-gray-400 hover:text-indigo-400 transition"
                  >
                    <RefreshCw className="h-3 w-3" />
                    <span>Reset Log</span>
                  </button>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm border-collapse">
                    <thead>
                      <tr className="border-b border-white/10 text-gray-400">
                        <th className="pb-3 font-semibold">Payment ID</th>
                        <th className="pb-3 font-semibold">Amount</th>
                        <th className="pb-3 font-semibold">Status</th>
                        <th className="pb-3 font-semibold">Idempotency Key</th>
                        <th className="pb-3 font-semibold">Gateway Ref</th>
                        <th className="pb-3 font-semibold">Created At</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-white/5">
                      {payments.map(pay => (
                        <tr key={pay.id} className="hover:bg-white/5 transition-colors">
                          <td className="py-4 font-mono font-medium text-indigo-400">{pay.id}</td>
                          <td className="py-4 font-semibold">
                            {pay.amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {pay.currency}
                          </td>
                          <td className="py-4">
                            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${
                              pay.status === "CAPTURED" || pay.status === "AUTHORIZED" 
                                ? "bg-green-500/10 text-green-400 border border-green-500/20"
                                : "bg-red-500/10 text-red-400 border border-red-500/20"
                            }`}>
                              {pay.status}
                            </span>
                            {pay.reasons && (
                              <div className="text-xs text-red-500 mt-1 max-w-xs">{pay.reasons[0]}</div>
                            )}
                          </td>
                          <td className="py-4 font-mono text-gray-400 text-xs">{pay.idempotencyKey}</td>
                          <td className="py-4 font-mono text-gray-400 text-xs">{pay.gatewayRef || "—"}</td>
                          <td className="py-4 text-gray-400 text-xs">
                            {new Date(pay.createdAt).toLocaleTimeString()}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: SANDBOX SIMULATOR */}
          {activeTab === "simulator" && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
              {/* Form panel */}
              <div className="p-6 rounded-2xl bg-white/5 border border-white/10 space-y-6 shadow-xl flex flex-col justify-between">
                <form onSubmit={handleSimulatePayment} className="space-y-4">
                  <h3 className="text-base font-semibold border-b border-white/10 pb-3 flex items-center space-x-2 text-indigo-400">
                    <CreditCard className="h-5 w-5" />
                    <span>Transaction Details</span>
                  </h3>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs text-gray-400 mb-1 font-medium">Amount</label>
                      <input 
                        type="text" 
                        value={amount} 
                        onChange={(e) => setAmount(e.target.value)}
                        className="w-full bg-[#12121e] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-600 font-mono"
                        disabled={isSimulating}
                        required
                      />
                    </div>
                    <div>
                      <label className="block text-xs text-gray-400 mb-1 font-medium">Currency</label>
                      <select 
                        value={currency} 
                        onChange={(e) => setCurrency(e.target.value)}
                        className="w-full bg-[#12121e] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-600 font-mono"
                        disabled={isSimulating}
                      >
                        <option value="USD">USD</option>
                        <option value="EUR">EUR</option>
                        <option value="GBP">GBP</option>
                        <option value="INR">INR</option>
                      </select>
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1 font-medium">Card Issuer Profile</label>
                    <select
                      value={cardProfile}
                      onChange={(e) => setCardProfile(e.target.value)}
                      className="w-full bg-[#12121e] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-600"
                      disabled={isSimulating}
                    >
                      <option value="success">Success Visa (Simulates approved network auth)</option>
                      <option value="decline">Decline Card (Simulates tok_decline insufficient funds)</option>
                      <option value="limit">High Amount Limit (Decline if &gt;= $10,000)</option>
                    </select>
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1 font-medium flex justify-between">
                      <span>Idempotency-Key (Header)</span>
                      <button 
                        type="button" 
                        onClick={generateIdempotencyKey}
                        className="text-indigo-400 hover:underline"
                        disabled={isSimulating}
                      >
                        Roll new
                      </button>
                    </label>
                    <input 
                      type="text" 
                      value={idempotencyKey} 
                      onChange={(e) => setIdempotencyKey(e.target.value)}
                      className="w-full bg-[#12121e] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-600 font-mono text-xs"
                      disabled={isSimulating}
                      required
                    />
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1 font-medium">Description</label>
                    <input 
                      type="text" 
                      value={description} 
                      onChange={(e) => setDescription(e.target.value)}
                      className="w-full bg-[#12121e] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-600"
                      disabled={isSimulating}
                    />
                  </div>

                  <button
                    type="submit"
                    disabled={isSimulating}
                    className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-600/30 text-white font-medium py-3 rounded-lg transition-colors flex items-center justify-center space-x-2 text-sm"
                  >
                    {isSimulating ? (
                      <>
                        <RefreshCw className="h-4 w-4 animate-spin" />
                        <span>Simulating Payment Flow...</span>
                      </>
                    ) : (
                      <>
                        <Cpu className="h-4 w-4" />
                        <span>Create Payment (SAGA Run)</span>
                      </>
                    )}
                  </button>
                </form>

                {simResult && (
                  <div className="mt-6 p-4 rounded-xl border border-white/10 bg-white/5 space-y-2 text-sm">
                    <h4 className="font-semibold text-white flex items-center space-x-2">
                      <CheckCircle2 className="h-4 w-4 text-green-400" />
                      <span>API Response (REST 201/402)</span>
                    </h4>
                    <pre className="text-xs bg-[#121218] p-3 rounded-lg overflow-x-auto text-cyan-400 font-mono">
                      {JSON.stringify(simResult, null, 2)}
                    </pre>
                  </div>
                )}
              </div>

              {/* Flow Visualizer panel */}
              <div className="p-6 rounded-2xl bg-[#0b0b12] border border-white/10 flex flex-col space-y-4 shadow-xl">
                <h3 className="text-base font-semibold border-b border-white/10 pb-3 flex items-center space-x-2 text-indigo-400">
                  <Layers className="h-5 w-5" />
                  <span>Distributed Flow Pipeline Logs</span>
                </h3>

                {/* Pipeline visualizer */}
                <div className="flex-1 space-y-6">
                  {/* Step List */}
                  <div className="space-y-4 text-sm relative">
                    <div className="absolute left-[13px] top-[10px] bottom-[10px] w-0.5 bg-white/10 z-0"></div>

                    {/* Step 1: API Gateway */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 1 ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        1
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">API Gateway & Verification</div>
                        {simStep >= 1 && <p className="text-xs text-gray-400">API Key checking, Redis token-bucket rate limiter.</p>}
                      </div>
                    </div>

                    {/* Step 2: SAGA Init */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 3 ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        2
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Idempotency & SAGA Log</div>
                        {simStep >= 3 && <p className="text-xs text-gray-400">Redis idempotency lock verified, initial DB logs committed.</p>}
                      </div>
                    </div>

                    {/* Step 3: Fraud Engine */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 4 ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        3
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Go Fraud Analysis Service</div>
                        {simStep >= 4 && <p className="text-xs text-gray-400">Sliding window velocity scan, location and blacklists check.</p>}
                      </div>
                    </div>

                    {/* Step 4: Issuer Network Auth */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 5 ? (simStep === 99 ? "bg-red-600 text-white shadow-lg shadow-red-600/30" : "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30") : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        4
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Gateway / Network Authorization</div>
                        {simStep >= 5 && <p className="text-xs text-gray-400">Simulating external network auth. Returns gateway code/reference.</p>}
                      </div>
                    </div>

                    {/* Step 5: Double Entry Ledger */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 6 ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        5
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Ledger Bookkeeping (Double Entry)</div>
                        {simStep >= 6 && <p className="text-xs text-gray-400">Assets and merchant payables balance incremented. Transaction inbox registered.</p>}
                      </div>
                    </div>

                    {/* Step 6: Kafka Publishing */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 7 ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        6
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Kafka Event Spine Publishing</div>
                        {simStep >= 7 && <p className="text-xs text-gray-400">Outbox publisher processes outbox table records into payment.authorized queue.</p>}
                      </div>
                    </div>

                    {/* Step 7: Go Webhook Dispatch */}
                    <div className="flex items-start space-x-4 relative z-10">
                      <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold font-mono transition-all ${
                        simStep >= 8 ? "bg-green-600 text-white shadow-lg shadow-green-600/30" : "bg-[#12121e] border border-white/10 text-gray-500"
                      }`}>
                        7
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">Go Webhook delivery (At-Least-Once)</div>
                        {simStep >= 8 && <p className="text-xs text-gray-400">Signature generated, POST webhook log committed to Postgres.</p>}
                      </div>
                    </div>
                  </div>
                </div>

                {/* Console Log window */}
                <div className="h-48 bg-black/60 border border-white/10 rounded-xl p-4 overflow-y-auto font-mono text-xs text-indigo-300 space-y-1 select-text">
                  {simLogs.map((logStr, i) => (
                    <div key={i} className={
                      logStr.includes("DECLINED") || logStr.includes("failure")
                        ? "text-red-400"
                        : logStr.includes("APPROVED") || logStr.includes("complete")
                          ? "text-green-400"
                          : ""
                    }>
                      &gt; {logStr}
                    </div>
                  ))}
                  {isSimulating && (
                    <div className="text-white animate-pulse">&gt; Processing...</div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: KEYS & CONFIGS */}
          {activeTab === "keys" && (
            <div className="space-y-6 max-w-3xl">
              <div className="p-6 rounded-2xl bg-white/5 border border-white/10 space-y-6 shadow-xl">
                <h3 className="text-base font-semibold border-b border-white/10 pb-3 flex items-center space-x-2 text-indigo-400">
                  <Key className="h-5 w-5" />
                  <span>Sandbox Integration Credentials</span>
                </h3>

                <div className="space-y-4">
                  <div>
                    <label className="block text-xs text-gray-400 mb-1">Merchant ID</label>
                    <div className="bg-[#12121e] border border-white/10 rounded-lg px-4 py-3 font-mono text-sm select-all">
                      merchant_demo_123
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1">Public Key (Client tokenization)</label>
                    <div className="bg-[#12121e] border border-white/10 rounded-lg px-4 py-3 font-mono text-sm select-all">
                      pk_test_51paycorePublicDemoKey2026
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1 flex items-center justify-between">
                      <span>Secret API Key</span>
                      <button 
                        onClick={() => setApiKeyVisible(!apiKeyVisible)}
                        className="text-gray-400 hover:text-indigo-400 transition"
                      >
                        {apiKeyVisible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </label>
                    <div className="bg-[#12121e] border border-white/10 rounded-lg px-4 py-3 font-mono text-sm flex items-center justify-between">
                      <span className="select-all">
                        {apiKeyVisible ? "sk_test_paycore_demo_key_2026" : "••••••••••••••••••••••••••••••••••••"}
                      </span>
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs text-gray-400 mb-1">Webhook Secret Key (HMAC signing)</label>
                    <div className="bg-[#12121e] border border-white/10 rounded-lg px-4 py-3 font-mono text-sm select-all">
                      whsec_mock_secret_key_2026
                    </div>
                  </div>
                </div>
              </div>

              <div className="p-6 rounded-2xl bg-white/5 border border-white/10 space-y-4 shadow-xl">
                <h3 className="text-base font-semibold flex items-center space-x-2 text-indigo-400">
                  <Database className="h-5 w-5" />
                  <span>Platform gRPC Endpoint Registry</span>
                </h3>
                <div className="text-sm text-gray-400 space-y-2 font-mono">
                  <div className="flex justify-between border-b border-white/5 py-2">
                    <span>payment-service</span>
                    <span className="text-white">localhost:50051 (gRPC)</span>
                  </div>
                  <div className="flex justify-between border-b border-white/5 py-2">
                    <span>authorization-service</span>
                    <span className="text-white">localhost:50052 (gRPC)</span>
                  </div>
                  <div className="flex justify-between border-b border-white/5 py-2">
                    <span>ledger-service</span>
                    <span className="text-white">localhost:50053 (gRPC)</span>
                  </div>
                  <div className="flex justify-between border-b border-white/5 py-2">
                    <span>fraud-detection-service</span>
                    <span className="text-white">localhost:50054 (gRPC) / localhost:8084 (REST)</span>
                  </div>
                  <div className="flex justify-between border-b border-white/5 py-2">
                    <span>webhook-service</span>
                    <span className="text-white">localhost:50055 (gRPC)</span>
                  </div>
                  <div className="flex justify-between py-2">
                    <span>auth-service</span>
                    <span className="text-white">localhost:50056 (gRPC)</span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
