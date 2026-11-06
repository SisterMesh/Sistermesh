import { BloodGroup } from '@prisma/client';

export const DONOR_GROUPS_FOR_RECIPIENT: Record<BloodGroup, BloodGroup[]> = {
  [BloodGroup.O_NEGATIVE]: [BloodGroup.O_NEGATIVE],
  [BloodGroup.O_POSITIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.O_POSITIVE],
  [BloodGroup.A_NEGATIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.A_NEGATIVE],
  [BloodGroup.A_POSITIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.O_POSITIVE, BloodGroup.A_NEGATIVE, BloodGroup.A_POSITIVE],
  [BloodGroup.B_NEGATIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.B_NEGATIVE],
  [BloodGroup.B_POSITIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.O_POSITIVE, BloodGroup.B_NEGATIVE, BloodGroup.B_POSITIVE],
  [BloodGroup.AB_NEGATIVE]: [BloodGroup.O_NEGATIVE, BloodGroup.A_NEGATIVE, BloodGroup.B_NEGATIVE, BloodGroup.AB_NEGATIVE],
  [BloodGroup.AB_POSITIVE]: Object.values(BloodGroup),
};

export function canDonateTo(donorGroup: BloodGroup, recipientGroup: BloodGroup): boolean {
  return DONOR_GROUPS_FOR_RECIPIENT[recipientGroup].includes(donorGroup);
}
