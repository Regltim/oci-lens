import { useId } from "react"
import { Check } from "lucide-react"
import { State } from "../ui"

export function SettingSwitch({label, description, checked, onChange, disabled}: {
  label: string; description?: string; checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean;
}) {
  const descriptionId = useId()
  return <label className="setting-switch">
    <span><strong>{label}</strong>{description && <small id={descriptionId}>{description}</small>}</span>
    <span className="switch-control"><input type="checkbox" role="switch" aria-label={label} aria-describedby={description ? descriptionId : undefined} checked={checked} disabled={disabled} onChange={event => onChange(event.target.checked)} /><span aria-hidden="true" /></span>
  </label>
}

export function FormFeedback({error, notice}: {error?: string; notice?: string}) {
  return <>{error && <State error={error} />}{notice && <p className="settings-feedback" role="status"><Check size={16} aria-hidden="true" />{notice}</p>}</>
}
