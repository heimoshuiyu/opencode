export * as SessionSkill from "./skill.js"

import type { Session } from "@opencode/schema/session"
import { Effect } from "effect"
import { Agent } from "../agent.js"
import { Instance } from "../instance/service.js"
import { Permission } from "../permission.js"
import { Plugin } from "../plugin/service.js"
import { Skill } from "../skill.js"
import { SkillNotFoundError } from "./error.js"

export const get = Effect.fn("SessionSkill.get")(function* (input: { session: Session.Info; skill: Skill.ID }) {
  const instances = yield* Instance.Service
  const { skills, agents } = yield* Effect.all({
    skills: Plugin.awaitActivation.pipe(Effect.andThen(Skill.Service)),
    agents: Agent.Service,
  }).pipe(instances.provide(input.session))
  const skill = yield* skills.get(input.skill)
  if (!skill) return yield* new SkillNotFoundError({ skill: input.skill })
  // User-driven activation never reaches the model's skill tool, so gate it
  // with the same deny evaluation; assert would add ask dialogs here.
  const agent = yield* agents.resolve(input.session.agent)
  if (agent && Permission.evaluate("skill", skill.id, agent.permissions).effect === "deny")
    return yield* new SkillNotFoundError({ skill: input.skill })
  return skill
})
