import { Agent } from "@opencode/core/agent"
import { Skill } from "@opencode/core/skill"
import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { Api } from "../api"
import { response } from "../location"

export const SkillHandler = HttpApiBuilder.group(Api, "server.skill", (handlers) =>
  handlers.handle("skill.list", () =>
    response(
      Effect.gen(function* () {
        const skills = yield* Skill.Service.use((skill) => skill.list())
        const agent = yield* Agent.Service.use((service) => service.resolve())
        // Catalog visibility mirrors the model-side filter in SkillInstructions:
        // skills denied for the default agent stay hidden from client-facing lists.
        return agent ? Skill.available(skills, agent) : skills
      }),
    ),
  ),
)
