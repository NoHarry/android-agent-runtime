---
name: event-planning
description: 用户要筹备一场聚会或活动（场地、来宾、流程）时：分头查场地和来宾安排，汇总成一份按时间排的流程，最后问要不要设提醒。
---

# 活动筹备

1. 先问清日期、人数和预算。
<!-- agent:event-venues -->
2. 用 spawn_agent 交给场地：agent 写 event-venues，任务写日期、人数和预算。
<!-- /agent:event-venues -->
<!-- no-agent:event-venues -->
2. 自己查场地：每家写容量、价位和能不能自带餐饮，场地页常列出套餐价。
<!-- /no-agent:event-venues -->
<!-- agent:event-guests -->
3. 用 spawn_agent 交给来宾：agent 写 event-guests，任务写来宾名单和通知时间。
<!-- /agent:event-guests -->
<!-- no-agent:event-guests -->
3. 自己排来宾通知：按名单列出每人什么时候通知。
<!-- /no-agent:event-guests -->
4. 汇总成按时间排的流程。
<!-- no-a2a -->
5. 最后问一句：「要不要设成提醒？」
<!-- /no-a2a -->
<!-- a2a -->
5. 最后问一句：「要不要设成提醒，或者把流程发给你的个人助手备份？」
<!-- /a2a -->
